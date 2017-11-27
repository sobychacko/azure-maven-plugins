/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.maven.function;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.azure.maven.Utils;
import com.microsoft.azure.maven.function.bindings.BaseBinding;
import com.microsoft.azure.maven.function.bindings.BindingFactory;
import com.microsoft.azure.maven.function.configurations.FunctionConfiguration;
import com.microsoft.azure.maven.function.handlers.AnnotationHandler;
import com.microsoft.azure.maven.function.handlers.AnnotationHandlerImpl;
import com.microsoft.azure.serverless.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.serverless.functions.annotation.HttpOutput;
import com.microsoft.azure.serverless.functions.annotation.HttpTrigger;
import org.apache.maven.model.Resource;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.reflections.Reflections;
import org.reflections.scanners.MethodParameterScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ConfigurationBuilder;

import org.springframework.cloud.function.adapter.azure.AzureSpringBootRequestHandler;

/**
 * Generate configuration files (host.json, function.json etc.) and copy JARs to staging directory.
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
public class PackageMojo extends AbstractFunctionMojo {
    public static final String SEARCH_FUNCTIONS = "Step 1 of 6: Searching for Azure Function entry points";
    public static final String FOUND_FUNCTIONS = " Azure Function entry point(s) found.";
    public static final String GENERATE_CONFIG = "Step 2 of 6: Generating Azure Function configurations";
    public static final String GENERATE_SKIP = "No Azure Functions found. Skip configuration generation.";
    public static final String GENERATE_DONE = "Generation done.";
    public static final String VALIDATE_CONFIG = "Step 3 of 6: Validating generated configurations";
    public static final String VALIDATE_SKIP = "No configurations found. Skip validation.";
    public static final String VALIDATE_DONE = "Validation done.";
    public static final String SAVE_HOST_JSON = "Step 4 of 6: Saving empty host.json";
    public static final String SAVE_FUNCTION_JSONS = "Step 5 of 6: Saving configurations to function.json";
    public static final String SAVE_SKIP = "No configurations found. Skip save.";
    public static final String SAVE_FUNCTION_JSON = "Starting processing function: ";
    public static final String SAVE_SUCCESS = "Successfully saved to ";
    public static final String COPY_JARS = "Step 6 of 6: Copying JARs to staging directory ";
    public static final String COPY_SUCCESS = "Copied successfully.";
    public static final String BUILD_SUCCESS = "Successfully built Azure Functions.";

    public static final String FUNCTION_JSON = "function.json";
    public static final String HOST_JSON = "host.json";

    //region Entry Point

    @Override
    protected void doExecute() throws Exception {

        final Set<Method> functionMethods = findFunctions(getClassUrl());
        Class<? extends AzureSpringBootRequestHandler> handlerClass = getHandlerClass(getClassUrl()).iterator().next();

        Method functionMethod = functionMethods.iterator().next();
        ParameterizedType genericReturnType = (ParameterizedType)functionMethod.getGenericReturnType();
        Type[] actualTypeArguments = genericReturnType.getActualTypeArguments();

        Method[] handlerClassMethods = handlerClass.getMethods();

        FunctionConfiguration functionConfiguration = new FunctionConfiguration();
        functionConfiguration.setScriptFile(getScriptFilePath());

        Stream<Method> methodStream = Stream.of(handlerClassMethods)
                .filter(m -> m.getReturnType().getCanonicalName().equals(actualTypeArguments[1].getTypeName()));

        Optional<Method> first = methodStream.findFirst();
        if (first.isPresent()) {
            Method handlerMethod = first.get();
            Class<?>[] parameterTypes1 = handlerMethod.getParameterTypes();
            Stream<Class<?>> classStream = Stream.of(parameterTypes1)
                    .filter(cl -> cl.getCanonicalName().equals(actualTypeArguments[0].getTypeName()));
            if(classStream.findFirst().isPresent()) {
                functionConfiguration.setEntryPoint(handlerClass.getCanonicalName() + "." + handlerMethod.getName());
            }
            else {
                error("No entry methods are found.");
            }
        }

        List<BaseBinding> bindings = functionConfiguration.getBindings();

        bindings.add(BindingFactory.getBinding(new MyHttpTrigger()));
        bindings.add(BindingFactory.getBinding(new MyHttpOutput()));

        final Map<String, FunctionConfiguration> configMap = new HashMap<>();
        configMap.put(functionMethods.iterator().next().getName(), functionConfiguration);

        final ObjectWriter objectWriter = getObjectWriter();

        writeEmptyHostJsonFile(objectWriter);

        writeFunctionJsonFiles(objectWriter, configMap);

        copyJarsToStageDirectory();

        info(BUILD_SUCCESS);
    }

    public Set<Method> findFunctions(final URL url) {
        return new Reflections(
                new ConfigurationBuilder()
                        .addUrls(url)
                        .addScanners(new MethodParameterScanner())
                        .addClassLoader(getClassLoader(url)))
                .getMethodsReturn(Function.class);
    }

    public Set<Class<? extends AzureSpringBootRequestHandler>> getHandlerClass(final URL url) {
        return new Reflections(
                new ConfigurationBuilder()
                        .addUrls(url)
                        .addScanners(new SubTypesScanner())
                        .addClassLoader(getClassLoader(url)))
                .getSubTypesOf(AzureSpringBootRequestHandler.class);
    }

    protected ClassLoader getClassLoader(final URL url) {
        return new URLClassLoader(new URL[]{url}, this.getClass().getClassLoader());
    }


    //endregion

    //region Process annotations

    protected AnnotationHandler getAnnotationHandler() throws Exception {
        return new AnnotationHandlerImpl(getLog());
    }

    protected Set<Method> findAnnotatedMethods(final AnnotationHandler handler) throws Exception {
        info("");
        info(SEARCH_FUNCTIONS);
        final Set<Method> functions = handler.findFunctions(getClassUrl());
        info(functions.size() + FOUND_FUNCTIONS);
        return functions;
    }

    protected URL getClassUrl() throws Exception {
        return outputDirectory.toURI().toURL();
    }

    //endregion

    //region Generate function configurations

    protected Map<String, FunctionConfiguration> getFunctionConfigurations(final AnnotationHandler handler,
                                                                           final Set<Method> methods) throws Exception {
        info("");
        info(GENERATE_CONFIG);
        final Map<String, FunctionConfiguration> configMap = handler.generateConfigurations(methods);
        if (configMap.size() == 0) {
            info(GENERATE_SKIP);
        } else {
            final String scriptFilePath = getScriptFilePath();
            configMap.values().forEach(config -> config.setScriptFile(scriptFilePath));
            info(GENERATE_DONE);
        }

        return configMap;
    }

    protected String getScriptFilePath() {
        return new StringBuilder()
                .append("..")
                .append(File.separator)
                .append(getFinalName())
                .append("-azure")
                .append(".jar")
                .toString();
    }

    //endregion

    //region Validate function configurations

    protected void validateFunctionConfigurations(final Map<String, FunctionConfiguration> configMap) {
        info("");
        info(VALIDATE_CONFIG);
        if (configMap.size() == 0) {
            info(VALIDATE_SKIP);
        } else {
            configMap.values().forEach(config -> config.validate());
            info(VALIDATE_DONE);
        }
    }

    //endregion

    //region Write configurations (host.json, function.json) to file

    protected void writeFunctionJsonFiles(final ObjectWriter objectWriter,
                                          final Map<String, FunctionConfiguration> configMap) throws IOException {
        info("");
        info(SAVE_FUNCTION_JSONS);
        if (configMap.size() == 0) {
            info(SAVE_SKIP);
        } else {
            for (final Map.Entry<String, FunctionConfiguration> config : configMap.entrySet()) {
                writeFunctionJsonFile(objectWriter, config.getKey(), config.getValue());
            }
        }
    }

    protected void writeFunctionJsonFile(final ObjectWriter objectWriter, final String functionName,
                                         final FunctionConfiguration config) throws IOException {
        info(SAVE_FUNCTION_JSON + functionName);
        final File functionJsonFile = Paths.get(getDeploymentStageDirectory(), functionName, FUNCTION_JSON).toFile();
        writeObjectToFile(objectWriter, config, functionJsonFile);
        info(SAVE_SUCCESS + functionJsonFile.getAbsolutePath());
    }

    protected void writeEmptyHostJsonFile(final ObjectWriter objectWriter) throws IOException {
        info("");
        info(SAVE_HOST_JSON);
        final File hostJsonFile = Paths.get(getDeploymentStageDirectory(), HOST_JSON).toFile();
        writeObjectToFile(objectWriter, new Object(), hostJsonFile);
        info(SAVE_SUCCESS + hostJsonFile.getAbsolutePath());
    }

    protected void writeObjectToFile(final ObjectWriter objectWriter, final Object object, final File targetFile)
            throws IOException {
        targetFile.getParentFile().mkdirs();
        targetFile.createNewFile();
        objectWriter.writeValue(targetFile, object);
    }

    protected ObjectWriter getObjectWriter() {
        return new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .writerWithDefaultPrettyPrinter();
    }

    //endregion

    //region Copy Jars to stage directory

    protected void copyJarsToStageDirectory() throws IOException {
        final String stagingDirectory = getDeploymentStageDirectory();
        info("");
        info(COPY_JARS + stagingDirectory);
        Utils.copyResources(
                getProject(),
                getSession(),
                getMavenResourcesFiltering(),
                getResources(),
                stagingDirectory);
        info(COPY_SUCCESS);
    }

    protected List<Resource> getResources() {
        final Resource resource = new Resource();
        resource.setDirectory(getBuildDirectoryAbsolutePath());
        resource.setTargetPath("/");
        resource.setFiltering(false);
        resource.setIncludes(Arrays.asList("*.jar"));
        return Arrays.asList(resource);
    }

    private static class MyHttpOutput implements HttpOutput {

        @Override
        public String name() {
            return "$return";
        }

        @Override
        public String dataType() {
            return "";
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return HttpOutput.class;
        }
    }

    private static class MyHttpTrigger implements HttpTrigger {

        @Override
		public Class<? extends Annotation> annotationType() {
			return HttpTrigger.class;
		}

        @Override
		public String name() {
			return "req";
		}

        @Override
		public String dataType() {
			return "";
		}

        @Override
		public String route() {
			return "";
		}

        @Override
		public String[] methods() {
			return new String[]{"get", "post"};
		}

        @Override
		public AuthorizationLevel authLevel() {
			return AuthorizationLevel.ANONYMOUS;
		}

        @Override
		public String webHookType() {
			return "";
		}

    }

    //endregion
}
