# Ant to Gradle Migration Plan

## 1. Introduction

This document outlines a plan to migrate the Mirth Connect build system from Ant to Gradle. The goal is to create a more modern, maintainable, and efficient build system. This plan focuses on migrating the build, development, and packaging of the applications, without any changes to the Java source code or the functionality of the applications.

## 2. Project Structure

The current multi-project structure will be maintained in Gradle. A root `build.gradle` file will be created in the main project directory, and each sub-project (`client`, `command`, `donkey`, `generator`, `manager`, `server`, `webadmin`) will have its own `build.gradle` file.

The new project structure will look like this:

```
open-integration-engine/
├── build.gradle
├── settings.gradle
├── client/
│   └── build.gradle
├── command/
│   └── build.gradle
├── donkey/
│   └── build.gradle
├── generator/
│   └── build.gradle
├── manager/
│   └── build.gradle
├── server/
│   └── build.gradle
└── webadmin/
    └── build.gradle
```

The `settings.gradle` file will define the sub-projects included in the build.

## 3. Dependency Management

The current dependency management system relies on JAR files stored in `lib` directories. This will be migrated to Gradle's dependency management system.

- **External Dependencies:** All external dependencies (e.g., commons-io, log4j) will be declared in the `dependencies` block of the `build.gradle` files. The dependencies will be downloaded from Maven Central or another specified repository.
- **Internal Dependencies:** Dependencies between sub-projects (e.g., `server` depending on `donkey`) will be declared as project dependencies in Gradle.

This change will centralize dependency management, simplify updates, and reduce the size of the codebase by removing the `lib` directories.

## 4. Build Scripts

Each sub-project will have its own `build.gradle` file. Here's a breakdown of the configuration for each:

### 4.1. `command/build.gradle`

- Apply the `java` plugin.
- Configure source and resource directories.
- Declare dependencies on external libraries (e.g., commons-cli).
- Create tasks to build the `mirth-cli.jar` and `mirth-cli-launcher.jar` files.
- Configure the manifest for the launcher JAR.
- Migrate the JUnit tests and JaCoCo code coverage.

### 4.2. `donkey/build.gradle`

- Apply the `java` plugin.
- Configure source and resource directories.
- Declare dependencies on external libraries.
- Create tasks to build the `donkey-server.jar` and `donkey-model.jar` files.
- Migrate the JUnit tests and JaCoCo code coverage.

### 4.3. `generator/build.gradle`

- Apply the `java` plugin.
- Create a custom task to run the `HL7ModelGenerator` and generate the vocabulary source code.
- Configure the build to compile the generated source code.
- Create a task to build the `model-generator.jar` and `mirth-vocab.jar` files.

### 4.4. `server/build.gradle`

- Apply the `java` plugin.
- Configure source and resource directories.
- Declare dependencies on other sub-projects (e.g., `donkey`) and external libraries.
- Create tasks to build the various JAR files (`mirth-client-core.jar`, `mirth-crypto.jar`, `mirth-server.jar`, etc.).
- Create tasks to build the extensions and plugins as separate JAR files.
- Create a task to sign the JARs for Java Web Start, using the existing keystore.
- Migrate the JUnit tests and JaCoCo code coverage.
- Create a task to generate the Javadocs.

### 4.5. `webadmin/build.gradle`

- Apply the `war` plugin.
- Configure the web application source and resource directories.
- Declare dependencies on other sub-projects and external libraries.
- Create a task to precompile the JSPs.
- Create a task to build the `webadmin.war` file.

### 4.6. `client/build.gradle` and `manager/build.gradle`

These projects are simpler and will require basic `java` plugin configuration to compile the source code and package it into JARs.

## 5. Root Build Script

The root `build.gradle` file will contain:

- Common configurations for all sub-projects (e.g., group, version, repositories).
- A `subprojects` block to apply common plugins and configurations to all sub-projects.
- A task to create the final distribution ZIP archives, which will include all the necessary files and directories.

## 6. Testing

The existing JUnit tests will be migrated to run with Gradle's `test` task. The JaCoCo plugin for Gradle will be used to generate code coverage reports, replacing the custom Ant task.

## 7. Distribution

The final distribution packages will be created using Gradle's `Zip` task. This will replace the `create-extension-zips` and `create-dist` targets in the Ant build.

## 8. Step-by-step Migration Guide

1.  **Create the Gradle project structure:** Create the root `build.gradle` and `settings.gradle` files, and a `build.gradle` file for each sub-project.
2.  **Migrate one project at a time:** Start with a project with no internal dependencies, like `donkey`.
3.  **Configure the build script:** Apply the necessary plugins, configure source sets, and declare dependencies.
4.  **Build the project:** Run the Gradle build and ensure that the output is the same as the Ant build.
5.  **Migrate the tests:** Configure the `test` task and the JaCoCo plugin.
6.  **Repeat for all projects:** Continue migrating the other projects, one by one.
7.  **Create the root build script:** Add common configurations and the distribution task to the root `build.gradle` file.
8.  **Remove the Ant build files:** Once the Gradle build is working correctly, the `build.xml` and `build.properties` files can be removed.
