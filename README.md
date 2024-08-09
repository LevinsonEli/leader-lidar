# LeaderLidar

LeaderLidar is a car safety vendor specializing in advanced safety solutions. The primary product, LeaderLidar, comprises the following packages:

- **telemetry**: Integrates with car metrics and formats them into an internal events protocol.
- **analytics**: Accepts events and determines actions such as "beep" or "brake".

Additionally, there are two other packages:
- **testing**: For testing purposes.
- **product**: Serves as a dependency for the main product.

Typically, you should have four separate repositories: one for each package.

This project demonstrates the use of Jenkins for CI/CD, dependency management, and Artifactory for artifact storage.

**#Jenkins #CI #JFrogArtifactory #MVN #DependencyManagement**

## Jenkins Pipelines Overview

### telemetry

- **Feature Branches (`feature/*`)**: Build and run unit tests for every commit (`mvn package`). If the commit message contains `#e2e`, end-to-end tests will also be executed using `analytics:99-SNAPSHOT`.
- **Main Branch**: Performs build, end-to-end tests, and publishes to Artifactory (`mvn deploy -DskipTests`).
- **Release Branches (`release/*`)**: Attempt a release following the rules outlined below.

### analytics

- **Feature Branches (`feature/*`)**: Follows the same logic as `telemetry`.
- **Main Branch**: Follows the same logic as `telemetry`.
- **Release Branches (`release/*`)**: Follows the same logic as `telemetry`.

### product

- **Release Branches (`release/*`)**: Only `release/*` branches are CIed. Other branches are ignored.

### testing

- **Main Branch**: Every commit performs build, end-to-end tests, and publishes to Artifactory (`mvn deploy -DskipTests`).

## Release Process

Releases follow these steps:

1. **Branch**: Use `release/x.y` for versions `x.y.z`.
2. **Version Adjustment**: Update the version in `pom.xml` to `x.y.z` (`mvn versions:set -DnewVersion=${YOUR_VERSION}`).
3. **Dependency Check**: Ensure all `com.lidar` dependencies are on the same `x.y` with the latest `z` (`mvn dependency:list`).
4. **Build and Test**: Perform build and unit tests (`mvn package`).
5. **End-to-End Tests**:
   - For `analytics` with version `x.y.z`, use the latest `telemetry` version `x.y`.
   - For `product`, use the `analytics` and `telemetry` JARs included in the product zip.
6. **Publish**: If all tests pass, publish to Artifactory (`mvn deploy -DskipTests`).
7. **Tag**: Create a Git tag for the version (`git tag x.y.z`).

## End-to-End Testing

1. **Fetch JARs**:
   - For `product`, retrieve JARs from the product zip.
   - For `analytics`, find one JAR in the `target/` folder and download the other from Artifactory using `curl`.

2. **Run Simulator**:
   - Ensure the simulator, `telemetry`, and `analytics` JARs are in the classpath: 
     ```bash
     java -cp <JAR1>:<JAR2>:<JAR3> com.lidar.simulation.Simulator
     ```
   - The simulator will run all tests found in `tests.txt` in its execution folder. Initially, use `tests-sanity.txt`, but aim to run `tests-full.txt` for comprehensive testing.

3. **Exit Code**: The simulator returns a non-zero exit code for failed tests.

### Notice:
The 'Jenkinsfile.groovy' files are copied from the shared library of Jenkins. For testing the pipelines you should have a shared-lib for Jenkins or find a work-around.