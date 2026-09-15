# Open Integration Engine

[![Website](https://img.shields.io/badge/website-openintegrationengine.org-blue)](https://openintegrationengine.org)
[![Discord](https://img.shields.io/discord/943670759891554316?label=Join%20our%20Discord&logo=discord&style=flat)](https://discord.gg/azdehW2Zrx)
[![Docker](https://img.shields.io/badge/docker-openintegrationengine-blue?logo=docker&style=flat)](https://hub.docker.com/u/openintegrationengine)
[![LinkedIn](https://img.shields.io/badge/linkedin-follow-blue?logo=linkedin&style=flat)](https://www.linkedin.com/company/open-integration-engine)

---
## OpenShare Engine (this branch)

The `openshare` branch is **OpenShare Engine**, [Saga IT](https://saga-it.com)'s build of the Open Integration Engine: upstream OIE plus OpenShare branding, signed Windows and Linux installers, a Java 21 runtime, and fixes that have not yet landed upstream. It is the source for every OpenShare Engine binary we distribute, and it is published here under the same [MPL 2.0](LICENSE) as upstream so that anyone receiving those binaries can obtain and modify the corresponding source.

- **Releases:** each build is tagged `openshare-v<upstream version>-<n>` (for example `openshare-v4.6.0-7`). The tag names the exact commit a set of installers and container images was built from; the media's provenance file records the same commit.
- **Binaries:** installers come from the engine download in the [OpenShare console](https://openshare.health); container images are on Docker Hub as [`sagait/openshare-engine`](https://hub.docker.com/r/sagait/openshare-engine).
- **Upstream:** [OpenIntegrationEngine/engine](https://github.com/OpenIntegrationEngine/engine). This branch is based on an upstream release tag (currently `v4.6.0`) rather than upstream `main`; fixes we make are offered upstream where they apply.
- **Building:** this branch still uses the Ant build (`server/mirth-build.xml`). It needs a JDK of at least 19 that bundles JavaFX (the Derby 10.17 tools jar is compiled for Java 19; the Administrator client compiles against JavaFX). `cd server && ant compile test-run` runs the server test suite.
- **Security:** OpenShare Engine 4.6.0-7 fixes CVE-2026-82583, CVE-2026-78224 and CVE-2026-82578 (CISA advisory ICSMA-26-253-01) and ports the upstream metadata-search fix (OIE #361). Report security issues to security@saga-it.com.

**Trademarks and branding.** "OpenShare" and the OpenShare logos are trademarks of Saga IT, LLC. The artwork under `branding/openshare/` and the client branding images (`client/src/com/mirth/connect/client/ui/images/branding/`, `server/public_api_html/images/`) are copyright Saga IT, LLC and are **not** licensed under the MPL; they are in this repository only so the branch builds as shipped. MPL 2.0 section 2.3 grants no trademark rights in any case. If you redistribute a modified build, replace the name and the branding with your own. "Mirth Connect" is a trademark of NextGen Healthcare; "Open Integration Engine" belongs to the upstream project.

The rest of this README is upstream's and describes the Open Integration Engine project itself.

---
## Table of Contents

- [Mission Statement](#mission-statement)
- [Overview](#overview)
- [Why Open Integration Engine?](#why-open-integration-engine)
- [Core Features](#core-features)
- [Who It’s For](#who-its-for)
- [Project Values](#project-values)
- [Community and Governance](#community-and-governance)
- [History and Roadmap](#history-and-roadmap)
- [Licensing](#licensing)
- [Acknowledgments](#acknowledgments)

---
## Mission Statement

To empower seamless healthcare interoperability through an open, community-driven integration engine that is accessible, extensible, and standards-based.

---

## Overview

The **Open Integration Engine Project** is an open-source initiative committed to advancing healthcare interoperability. Building upon the foundation of **Mirth Connect**, a widely adopted integration engine, this project aims to provide healthcare organizations with a robust, flexible, and cost-effective solution for connecting disparate systems and facilitating efficient data exchange.

---

## Why Open Integration Engine?

- **Open Source Advantage**  
  Transparency, innovation, and collaborative development within the healthcare IT community.

- **Healthcare-Focused**  
  Designed to address the unique challenges of healthcare data integration, supporting standards such as HL7, FHIR, and DICOM.

- **Extensible and Customizable**  
  Modular architecture for customization and expansion to meet evolving needs.

---

## Core Features

- **Data Transformation and Mapping**  
  Convert between HL7, JSON, XML, and other formats to ensure cross-system compatibility.

- **Message Routing and Filtering**  
  Route messages using flexible, rules-based logic to streamline workflows.

- **Custom Scripting and Connectors**  
  JavaScript-based scripting and support for protocols like HTTP/S, FTP, and database connectivity.

- **Real-Time Monitoring and Alerts**  
  Visual tools for tracking message flow, performance metrics, and setting up alerts.

---

## Who It’s For

- **Integration Engineers** – Looking for a flexible, open platform for managing interfaces  
- **Health IT Teams** – Connecting EHRs, LIS, RIS, and other healthcare systems  
- **EHR Vendors** – Enhancing interoperability within their products  
- **Researchers** – Requiring reliable, standards-based integration tools

---

## Project Values

- **Community-Driven Development** – Innovation through global collaboration  
- **Vendor Neutrality** – Free from proprietary constraints  
- **Security and Compliance** – HIPAA/GDPR-compliant architecture

---

## Community and Governance

Engage with the community and project through:

- 🌐 **Website**: [openintegrationengine.org](https://openintegrationengine.org)  
- 💬 **Discord**: [Join our server](https://discord.gg/azdehW2Zrx)  
- 📂 **GitHub Repo**: [github.com/OpenIntegrationEngine/engine](https://github.com/OpenIntegrationEngine/engine)  
- 📥 **Releases**: [Latest Releases](https://github.com/OpenIntegrationEngine/engine/releases)  
- 🗳️ **Governance**: [Governance Docs](https://github.com/OpenIntegrationEngine/governance)  
- 🐳 **Docker Hub**: [OpenIntegrationEngine on Docker Hub](https://hub.docker.com/u/openintegrationengine)
- 🔗 **LinkedIn**: [Follow us on LinkedIn](https://www.linkedin.com/company/open-integration-engine)

Governance is structured to be transparent and inclusive, with decisions made collectively to reflect the community’s best interests.

---

## History and Roadmap

Forked from **Mirth Connect** following its shift to a proprietary model, this project continues the legacy of open healthcare integration.

### Roadmap

**Short-Term Goals**
- Stabilize codebase  
- Improve documentation  
- Expand community involvement

**Long-Term Vision**
- Support new healthcare standards  
- Enhance UX/UI  
- Grow plugin ecosystem

---

## Licensing

Licensed under the **Mozilla Public License 2.0** (MPL 2.0). See [LICENSE](./LICENSE) for details.

---

## Acknowledgments

Special thanks to the original **Mirth Connect** team and the broader open-source healthcare integration community.
