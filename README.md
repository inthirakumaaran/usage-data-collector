# WSO2 Usage Data Collector

This repository contains usage data collectors for WSO2 products(APIM, MI, and IAM.). These collectors gather anonymized usage statistics and metrics from WSO2 products and send them to a centralized usage data receiver.

## Repository Structure
```
usage-data-collector/
├── collectors/
    ├── org.wso2.carbon.usage.data.collector.apim/          # API Manager collector
    ├── org.wso2.carbon.usage.data.collector.apim-gateway/  # API Manager Gateway collector
    ├── org.wso2.carbon.usage.data.collector.common/        # Common collector (shared)
    ├── org.wso2.carbon.usage.data.collector.identity/      # Identity Server collector
    └── org.wso2.carbon.usage.data.collector.mi/            # Micro Integrator collector

```

## Module to Product Mapping

| Module | WSO2 Product | Description |
|--------|--------------|-------------|
| `org.wso2.carbon.usage.data.collector.common` | All Products | Collects deployment meta infos (Core, JAVA version, OS) |
| `org.wso2.carbon.usage.data.collector.identity` | WSO2 Identity Server | Collects identity and access management usage data |
| `org.wso2.carbon.usage.data.collector.apim` | WSO2 API Manager | Collects API management usage data |
| `org.wso2.carbon.usage.data.collector.apim-gateway` | WSO2 API Manager Gateway | Collects API gateway usage data |
| `org.wso2.carbon.usage.data.collector.mi` | WSO2 Micro Integrator | Collects integration usage data |

## Prerequisites

- **Java**: JDK 8 or later
- **Maven**: 3.6.x or later
- **Git**: For version control

## Building the Project

### Build Module

No root pom.xml, has to build each package seperately.

eg:
```bash
# Build only the common library
cd collectors/org.wso2.carbon.usage.data.collector.common
mvn clean install

# Build only the Identity Server collector
cd collectors/org.wso2.carbon.usage.data.collector.identity
mvn clean install
```

### Build Options
```bash
# Skip tests
mvn clean install -DskipTests

# Skip checkstyle and spotbugs
mvn clean install -Dcheckstyle.skip -Dspotbugs.skip

# Build with specific Maven profile
mvn clean install -P wso2-release
```

## Release Process

### Using GitHub Actions (Automated)

Releases are done using GitHub Actions workflows. In the actions use the `Release Package` action.

**Note:** For the next developer version bump tick the `Create Pull Request for version bump` option


## Related Repositories

- [usage-data-receiver](https://github.com/wso2-enterprise/usage-data-receiver) - Centralized receiver for usage data
