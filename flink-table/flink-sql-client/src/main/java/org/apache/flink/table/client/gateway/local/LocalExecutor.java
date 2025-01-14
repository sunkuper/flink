/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.client.gateway.local;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.ClientUtils;
import org.apache.flink.client.cli.CliFrontend;
import org.apache.flink.client.cli.CliFrontendParser;
import org.apache.flink.client.cli.CustomCommandLine;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.plugin.PluginUtils;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.table.api.QueryConfig;
import org.apache.flink.table.api.StreamQueryConfig;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.client.SqlClientException;
import org.apache.flink.table.client.config.Environment;
import org.apache.flink.table.client.config.entries.TableEntry;
import org.apache.flink.table.client.config.entries.ViewEntry;
import org.apache.flink.table.client.gateway.Executor;
import org.apache.flink.table.client.gateway.ProgramTargetDescriptor;
import org.apache.flink.table.client.gateway.ResultDescriptor;
import org.apache.flink.table.client.gateway.SessionContext;
import org.apache.flink.table.client.gateway.SqlExecutionException;
import org.apache.flink.table.client.gateway.TypedResult;
import org.apache.flink.table.client.gateway.local.result.BasicResult;
import org.apache.flink.table.client.gateway.local.result.ChangelogResult;
import org.apache.flink.table.client.gateway.local.result.DynamicResult;
import org.apache.flink.table.client.gateway.local.result.MaterializedResult;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.utils.LogicalTypeUtils;
import org.apache.flink.table.types.utils.DataTypeUtils;
import org.apache.flink.types.Row;
import org.apache.flink.util.StringUtils;

import org.apache.flink.shaded.guava18.com.google.common.collect.ImmutableMap;

import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Executor that performs the Flink communication locally. The calls are blocking depending on the
 * response time to the Flink cluster. Flink jobs are not blocking.
 */
public class LocalExecutor implements Executor {

	private static final Logger LOG = LoggerFactory.getLogger(LocalExecutor.class);

	private static final String DEFAULT_ENV_FILE = "sql-client-defaults.yaml";

	// Map to hold all the available sessions. the key is session identifier, and the value is the ExecutionContext
	// created by the session context.
	private final ConcurrentHashMap<String, ExecutionContext<?>> contextMap;

	// deployment

	private final ClusterClientServiceLoader clusterClientServiceLoader;
	private final Environment defaultEnvironment;
	private final List<URL> dependencies;
	private final Configuration flinkConfig;
	private final List<CustomCommandLine> commandLines;
	private final Options commandLineOptions;

	// result maintenance

	private final ResultStore resultStore;

	/**
	 * Creates a local executor for submitting table programs and retrieving results.
	 */
	public LocalExecutor(URL defaultEnv, List<URL> jars, List<URL> libraries) {
		// discover configuration
		final String flinkConfigDir;
		try {
			// find the configuration directory
			flinkConfigDir = CliFrontend.getConfigurationDirectoryFromEnv();

			// load the global configuration
			this.flinkConfig = GlobalConfiguration.loadConfiguration(flinkConfigDir);

			// initialize default file system
			FileSystem.initialize(flinkConfig, PluginUtils.createPluginManagerFromRootFolder(flinkConfig));

			// load command lines for deployment
			this.commandLines = CliFrontend.loadCustomCommandLines(flinkConfig, flinkConfigDir);
			this.commandLineOptions = collectCommandLineOptions(commandLines);
		} catch (Exception e) {
			throw new SqlClientException("Could not load Flink configuration.", e);
		}

		// try to find a default environment
		if (defaultEnv == null) {
			final String defaultFilePath = flinkConfigDir + "/" + DEFAULT_ENV_FILE;
			System.out.println("No default environment specified.");
			System.out.print("Searching for '" + defaultFilePath + "'...");
			final File file = new File(defaultFilePath);
			if (file.exists()) {
				System.out.println("found.");
				try {
					defaultEnv = Path.fromLocalFile(file).toUri().toURL();
				} catch (MalformedURLException e) {
					throw new SqlClientException(e);
				}
				LOG.info("Using default environment file: {}", defaultEnv);
			} else {
				System.out.println("not found.");
			}
		}

		// inform user
		if (defaultEnv != null) {
			System.out.println("Reading default environment from: " + defaultEnv);
			try {
				defaultEnvironment = Environment.parse(defaultEnv);
			} catch (IOException e) {
				throw new SqlClientException("Could not read default environment file at: " + defaultEnv, e);
			}
		} else {
			defaultEnvironment = new Environment();
		}
		this.contextMap = new ConcurrentHashMap<>();

		// discover dependencies
		dependencies = discoverDependencies(jars, libraries);

		// prepare result store
		resultStore = new ResultStore(flinkConfig);

		clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
	}

	/**
	 * Constructor for testing purposes.
	 */
	public LocalExecutor(
			Environment defaultEnvironment,
			List<URL> dependencies,
			Configuration flinkConfig,
			CustomCommandLine commandLine,
			ClusterClientServiceLoader clusterClientServiceLoader) {
		this.defaultEnvironment = defaultEnvironment;
		this.dependencies = dependencies;
		this.flinkConfig = flinkConfig;
		this.commandLines = Collections.singletonList(commandLine);
		this.commandLineOptions = collectCommandLineOptions(commandLines);
		this.contextMap = new ConcurrentHashMap<>();

		// prepare result store
		this.resultStore = new ResultStore(flinkConfig);
		this.clusterClientServiceLoader = checkNotNull(clusterClientServiceLoader);
	}

	@Override
	public void start() {
		// nothing to do yet
	}

	/**
	 * Create a new {@link ExecutionContext} by merging the default environment the the environment in session context.
	 */
	private ExecutionContext<?> createExecutionContext(SessionContext sessionContext) {
		Environment mergedEnv = Environment.merge(defaultEnvironment, sessionContext.getSessionEnv());
		return createExecutionContext(mergedEnv, sessionContext);
	}

	/**
	 * Create a new {@link ExecutionContext} by using the given environment.
	 */
	private ExecutionContext<?> createExecutionContext(Environment environment, SessionContext sessionContext) {
		try {
			return new ExecutionContext<>(
					environment,
					sessionContext,
					dependencies,
					flinkConfig,
					clusterClientServiceLoader,
					commandLineOptions,
					commandLines);
		} catch (Throwable t) {
			// catch everything such that a configuration does not crash the executor
			throw new SqlExecutionException("Could not create execution context.", t);
		}
	}

	@Override
	public String openSession(SessionContext sessionContext) throws SqlExecutionException {
		String sessionId = sessionContext.getSessionId();
		ExecutionContext previousContext = this.contextMap.putIfAbsent(sessionId, createExecutionContext(sessionContext));
		if (previousContext != null) {
			throw new SqlExecutionException("Found another session with the same session identifier: " + sessionId);
		}
		return sessionId;
	}

	@Override
	public void closeSession(String sessionId) throws SqlExecutionException {
		resultStore.getResults().forEach((resultId) -> {
			try {
				cancelQuery(sessionId, resultId);
			} catch (Throwable t) {
				// ignore any throwable to keep the clean up running
			}
		});
		// Remove the session's ExecutionContext from contextMap.
		this.contextMap.remove(sessionId);
	}

	/**
	 * Get the existed {@link ExecutionContext} from contextMap, or thrown exception if does not exist.
	 */
	private ExecutionContext<?> getExecutionContext(String sessionId) throws SqlExecutionException {
		ExecutionContext<?> context = this.contextMap.get(sessionId);
		if (context == null) {
			throw new SqlExecutionException("Invalid session identifier: " + sessionId);
		}
		return context;
	}

	@Override
	public Map<String, String> getSessionProperties(String sessionId) throws SqlExecutionException {
		final Environment env = getExecutionContext(sessionId).getEnvironment();
		final Map<String, String> properties = new HashMap<>();
		properties.putAll(env.getExecution().asTopLevelMap());
		properties.putAll(env.getDeployment().asTopLevelMap());
		properties.putAll(env.getConfiguration().asMap());
		return properties;
	}

	@Override
	public void resetSessionProperties(String sessionId) throws SqlExecutionException {
		ExecutionContext<?> context = getExecutionContext(sessionId);
		// Renew the ExecutionContext by merging the default environment with original session context.
		this.contextMap.put(sessionId, createExecutionContext(context.getOriginalSessionContext()));
	}

	@Override
	public void setSessionProperty(String sessionId, String key, String value) throws SqlExecutionException {
		ExecutionContext<?> context = getExecutionContext(sessionId);
		Environment env = context.getEnvironment();
		Environment newEnv = Environment.enrich(env, ImmutableMap.of(key, value), ImmutableMap.of());
		// Renew the ExecutionContext by new environment.
		this.contextMap.put(sessionId, createExecutionContext(newEnv, context.getOriginalSessionContext()));
	}

	@Override
	public void addView(String sessionId, String name, String query) throws SqlExecutionException {
		ExecutionContext<?> context = getExecutionContext(sessionId);
		TableEnvironment tableEnv = context.getTableEnvironment();
		tableEnv.createTemporaryView(name, tableEnv.sqlQuery(query));
		// Also attach the view to ExecutionContext#environment.
		context.getEnvironment().getTables().put(name, ViewEntry.create(name, query));
	}

	@Override
	public void removeView(String sessionId, String name) throws SqlExecutionException {
		// Here we rebuild the ExecutionContext because we want to ensure that all the remaining views can work fine.
		// Assume the case:
		//   view1=select 1;
		//   view2=select * from view1;
		// If we delete view1 successfully, then query view2 will throw exception because view1 does not exist. we want
		// all the remaining views are OK, so do the ExecutionContext rebuilding to avoid breaking the view dependency.
		ExecutionContext<?> context = getExecutionContext(sessionId);
		Environment env = context.getEnvironment();
		Environment newEnv = env.clone();
		if (newEnv.getTables().remove(name) != null) {
			// Renew the ExecutionContext.
			this.contextMap.put(sessionId, createExecutionContext(newEnv, context.getOriginalSessionContext()));
		}
	}

	@Override
	public Map<String, ViewEntry> listViews(String sessionId) throws SqlExecutionException {
		Map<String, ViewEntry> views = new HashMap<>();
		Map<String, TableEntry> tables = getExecutionContext(sessionId).getEnvironment().getTables();
		for (Map.Entry<String, TableEntry> entry : tables.entrySet()) {
			if (entry.getValue() instanceof ViewEntry) {
				views.put(entry.getKey(), (ViewEntry) entry.getValue());
			}
		}
		return views;
	}

	@Override
	public List<String> listCatalogs(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listCatalogs()));
	}

	@Override
	public List<String> listDatabases(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listDatabases()));
	}

	@Override
	public List<String> listTables(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listTables()));
	}

	@Override
	public List<String> listUserDefinedFunctions(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listUserDefinedFunctions()));
	}

	@Override
	public List<String> listFunctions(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listFunctions()));
	}

	@Override
	public List<String> listModules(String sessionId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		return context.wrapClassLoader(() -> Arrays.asList(tableEnv.listModules()));
	}

	@Override
	public void useCatalog(String sessionId, String catalogName) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();

		context.wrapClassLoader(() -> {
			// Rely on TableEnvironment/CatalogManager to validate input
			try {
				tableEnv.useCatalog(catalogName);
			} catch (CatalogException e) {
				throw new SqlExecutionException("Failed to switch to catalog " + catalogName, e);
			}
			return null;
		});
	}

	@Override
	public void useDatabase(String sessionId, String databaseName) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();

		context.wrapClassLoader(() -> {
			// Rely on TableEnvironment/CatalogManager to validate input
			try {
				tableEnv.useDatabase(databaseName);
			} catch (CatalogException e) {
				throw new SqlExecutionException("Failed to switch to database " + databaseName, e);
			}
			return null;
		});
	}

	@Override
	public TableSchema getTableSchema(String sessionId, String name) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		try {
			return context.wrapClassLoader(() -> tableEnv.scan(name).getSchema());
		} catch (Throwable t) {
			// catch everything such that the query does not crash the executor
			throw new SqlExecutionException("No table with this name could be found.", t);
		}
	}

	@Override
	public String explainStatement(String sessionId, String statement) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();
		// translate
		try {
			final Table table = createTable(context, tableEnv, statement);
			return context.wrapClassLoader(() -> tableEnv.explain(table));
		} catch (Throwable t) {
			// catch everything such that the query does not crash the executor
			throw new SqlExecutionException("Invalid SQL statement.", t);
		}
	}

	@Override
	public List<String> completeStatement(String sessionId, String statement, int position) {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		final TableEnvironment tableEnv = context.getTableEnvironment();

		try {
			return context.wrapClassLoader(() ->
					Arrays.asList(tableEnv.getCompletionHints(statement, position)));
		} catch (Throwable t) {
			// catch everything such that the query does not crash the executor
			if (LOG.isDebugEnabled()) {
				LOG.debug("Could not complete statement at " + position + ":" + statement, t);
			}
			return Collections.emptyList();
		}
	}

	@Override
	public ResultDescriptor executeQuery(String sessionId, String query) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		return executeQueryInternal(sessionId, context, query);
	}

	@Override
	public TypedResult<List<Tuple2<Boolean, Row>>> retrieveResultChanges(
			String sessionId,
			String resultId) throws SqlExecutionException {
		final DynamicResult<?> result = resultStore.getResult(resultId);
		if (result == null) {
			throw new SqlExecutionException("Could not find a result with result identifier '" + resultId + "'.");
		}
		if (result.isMaterialized()) {
			throw new SqlExecutionException("Invalid result retrieval mode.");
		}
		return ((ChangelogResult<?>) result).retrieveChanges();
	}

	@Override
	public TypedResult<Integer> snapshotResult(String sessionId, String resultId, int pageSize) throws SqlExecutionException {
		final DynamicResult<?> result = resultStore.getResult(resultId);
		if (result == null) {
			throw new SqlExecutionException("Could not find a result with result identifier '" + resultId + "'.");
		}
		if (!result.isMaterialized()) {
			throw new SqlExecutionException("Invalid result retrieval mode.");
		}
		return ((MaterializedResult<?>) result).snapshot(pageSize);
	}

	@Override
	public List<Row> retrieveResultPage(String resultId, int page) throws SqlExecutionException {
		final DynamicResult<?> result = resultStore.getResult(resultId);
		if (result == null) {
			throw new SqlExecutionException("Could not find a result with result identifier '" + resultId + "'.");
		}
		if (!result.isMaterialized()) {
			throw new SqlExecutionException("Invalid result retrieval mode.");
		}
		return ((MaterializedResult<?>) result).retrievePage(page);
	}

	@Override
	public void cancelQuery(String sessionId, String resultId) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		cancelQueryInternal(context, resultId);
	}

	@Override
	public ProgramTargetDescriptor executeUpdate(String sessionId, String statement) throws SqlExecutionException {
		final ExecutionContext<?> context = getExecutionContext(sessionId);
		return executeUpdateInternal(sessionId, context, statement);
	}

	// --------------------------------------------------------------------------------------------

	private <T> void cancelQueryInternal(ExecutionContext<T> context, String resultId) {
		final DynamicResult<T> result = resultStore.getResult(resultId);
		if (result == null) {
			throw new SqlExecutionException("Could not find a result with result identifier '" + resultId + "'.");
		}

		// stop retrieval and remove the result
		LOG.info("Cancelling job {} and result retrieval.", resultId);
		result.close();
		resultStore.removeResult(resultId);

		// stop Flink job
		try (final ClusterDescriptor<T> clusterDescriptor = context.createClusterDescriptor()) {
			ClusterClient<T> clusterClient = null;
			try {
				// retrieve existing cluster
				clusterClient = clusterDescriptor.retrieve(context.getClusterId());
				try {
					clusterClient.cancel(new JobID(StringUtils.hexStringToByte(resultId))).get();
				} catch (Throwable t) {
					// the job might has finished earlier
				}
			} catch (Exception e) {
				throw new SqlExecutionException("Could not retrieve or create a cluster.", e);
			} finally {
				try {
					if (clusterClient != null) {
						clusterClient.close();
					}
				} catch (Exception e) {
					// ignore
				}
			}
		} catch (SqlExecutionException e) {
			throw e;
		} catch (Exception e) {
			throw new SqlExecutionException("Could not locate a cluster.", e);
		}
	}

	private <C> ProgramTargetDescriptor executeUpdateInternal(
			String sessionId,
			ExecutionContext<C> context,
			String statement) {
		applyUpdate(context, context.getTableEnvironment(), context.getQueryConfig(), statement);

		// create job graph with dependencies
		final String jobName = sessionId + ": " + statement;
		final JobGraph jobGraph;
		try {
			jobGraph = context.createJobGraph(jobName);
		} catch (Throwable t) {
			// catch everything such that the statement does not crash the executor
			throw new SqlExecutionException("Invalid SQL statement.", t);
		}

		// create execution
		final BasicResult<C> result = new BasicResult<>();
		final ProgramDeployer<C> deployer = new ProgramDeployer<>(
				context, jobName, jobGraph, result, false);

		// blocking deployment
		deployer.run();

		return ProgramTargetDescriptor.of(
				result.getClusterId(),
				jobGraph.getJobID(),
				result.getWebInterfaceUrl());
	}

	private <C> ResultDescriptor executeQueryInternal(String sessionId, ExecutionContext<C> context, String query) {
		// create table
		final Table table = createTable(context, context.getTableEnvironment(), query);

		// initialize result
		final DynamicResult<C> result = resultStore.createResult(
				context.getEnvironment(),
				removeTimeAttributes(table.getSchema()),
				context.getExecutionConfig());

		// create job graph with dependencies
		final String jobName = sessionId + ": " + query;
		final JobGraph jobGraph;
		try {
			// writing to a sink requires an optimization step that might reference UDFs during code compilation
			context.wrapClassLoader(() -> {
				context.getTableEnvironment().registerTableSink(jobName, result.getTableSink());
				table.insertInto(
						context.getQueryConfig(),
						jobName);
				return null;
			});
			jobGraph = context.createJobGraph(jobName);
		} catch (Throwable t) {
			// the result needs to be closed as long as
			// it not stored in the result store
			result.close();
			// catch everything such that the query does not crash the executor
			throw new SqlExecutionException("Invalid SQL query.", t);
		}

		// store the result with a unique id (the job id for now)
		final String resultId = jobGraph.getJobID().toString();
		resultStore.storeResult(resultId, result);

		// create execution
		final ProgramDeployer<C> deployer = new ProgramDeployer<>(
				context, jobName, jobGraph, result, true);

		// start result retrieval
		result.startRetrieval(deployer);

		return new ResultDescriptor(
				resultId,
				removeTimeAttributes(table.getSchema()),
				result.isMaterialized());
	}

	/**
	 * Creates a table using the given query in the given table environment.
	 */
	private <C> Table createTable(ExecutionContext<C> context, TableEnvironment tableEnv, String selectQuery) {
		// parse and validate query
		try {
			return context.wrapClassLoader(() -> tableEnv.sqlQuery(selectQuery));
		} catch (Throwable t) {
			// catch everything such that the query does not crash the executor
			throw new SqlExecutionException("Invalid SQL statement.", t);
		}
	}

	/**
	 * Applies the given update statement to the given table environment with query configuration.
	 */
	private <C> void applyUpdate(ExecutionContext<C> context, TableEnvironment tableEnv, QueryConfig queryConfig, String updateStatement) {
		// parse and validate statement
		try {
			context.wrapClassLoader(() -> {
				if (tableEnv instanceof StreamTableEnvironment) {
					((StreamTableEnvironment) tableEnv).sqlUpdate(updateStatement, (StreamQueryConfig) queryConfig);
				} else {
					tableEnv.sqlUpdate(updateStatement);
				}
				return null;
			});
		} catch (Throwable t) {
			// catch everything such that the statement does not crash the executor
			throw new SqlExecutionException("Invalid SQL update statement.", t);
		}
	}

	// --------------------------------------------------------------------------------------------

	private static List<URL> discoverDependencies(List<URL> jars, List<URL> libraries) {
		final List<URL> dependencies = new ArrayList<>();
		try {
			// find jar files
			for (URL url : jars) {
				ClientUtils.checkJarFile(url);
				dependencies.add(url);
			}

			// find jar files in library directories
			for (URL libUrl : libraries) {
				final File dir = new File(libUrl.toURI());
				if (!dir.isDirectory()) {
					throw new SqlClientException("Directory expected: " + dir);
				} else if (!dir.canRead()) {
					throw new SqlClientException("Directory cannot be read: " + dir);
				}
				final File[] files = dir.listFiles();
				if (files == null) {
					throw new SqlClientException("Directory cannot be read: " + dir);
				}
				for (File f : files) {
					// only consider jars
					if (f.isFile() && f.getAbsolutePath().toLowerCase().endsWith(".jar")) {
						final URL url = f.toURI().toURL();
						ClientUtils.checkJarFile(url);
						dependencies.add(url);
					}
				}
			}
		} catch (Exception e) {
			throw new SqlClientException("Could not load all required JAR files.", e);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Using the following dependencies: {}", dependencies);
		}

		return dependencies;
	}

	private static Options collectCommandLineOptions(List<CustomCommandLine> commandLines) {
		final Options customOptions = new Options();
		for (CustomCommandLine customCommandLine : commandLines) {
			customCommandLine.addGeneralOptions(customOptions);
			customCommandLine.addRunOptions(customOptions);
		}
		return CliFrontendParser.mergeOptions(
			CliFrontendParser.getRunCommandOptions(),
			customOptions);
	}

	private static TableSchema removeTimeAttributes(TableSchema schema) {
		final TableSchema.Builder builder = TableSchema.builder();
		for (int i = 0; i < schema.getFieldCount(); i++) {
			final DataType dataType = schema.getFieldDataTypes()[i];
			final DataType convertedType = DataTypeUtils.replaceLogicalType(
				dataType,
				LogicalTypeUtils.removeTimeAttributes(dataType.getLogicalType()));
			builder.field(schema.getFieldNames()[i], convertedType);
		}
		return builder.build();
	}
}
