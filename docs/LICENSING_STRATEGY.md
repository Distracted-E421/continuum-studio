# Licensing Strategy: Continuum Studio & Synapsix

> **Decision Date**: January 2026  
> **Status**: Strategic Direction - Pending Legal Review

---

## Philosophy

**Mission**: Democratize AI compute and infrastructure, empowering independent developers while preventing exploitation by big tech corporations.

**Core Values**:
- Free access for individuals, indie devs, small businesses, non-profits
- Force big tech to either contribute back fully or not use at all
- Enable a sustainable business model through enterprise licensing and services

---

## License Assignments

| Component | License | Rationale |
|-----------|---------|-----------|
| **Continuum DNS** | SSPL | Forces service providers to open source ALL infrastructure |
| **Custom CoreDNS Plugins** | SSPL | Part of DNS infrastructure |
| **Synapsix Orchestrator** | AGPL | Requires source sharing for network use |
| **Harness Implementations** | AGPL | Encourages contribution ecosystem |
| **Client SDKs** | AGPL | Network use requires sharing |
| **Documentation** | CC BY-SA 4.0 | Shareable, attributable |
| **Example Code** | MIT | Low barrier to adoption |

---

## Why SSPL for Infrastructure

### What SSPL Does

The Server Side Public License (SSPL) is based on the GNU Affero GPL but strengthens the copyleft requirement for service providers:

> If you make the functionality of the Program or a modified version available to third parties as a service, you must make the Service Source Code available via network download to everyone at no charge.

**"Service Source Code"** means ALL software you use to make the service available, including:
- Management software
- User interfaces  
- Application program interfaces
- Automation software
- Monitoring software
- Backup software
- Storage software
- Networking software

### Effect on Big Tech

| Company | Would They... | Likely Decision |
|---------|--------------|-----------------|
| AWS | Open source AWS's entire infrastructure? | **No** → Won't use |
| Google | Open source GCP internals? | **No** → Won't use |
| Microsoft | Open source Azure stack? | **No** → Won't use |
| Meta | Open source their infra? | **No** → Won't use |

### Effect on Individuals & Small Teams

| User Type | Requirement | Practical Impact |
|-----------|-------------|------------------|
| Individual dev | Source available to users | Easy: just host on GitHub |
| Small startup | Share modifications | Already planning to contribute |
| Self-hosted team | No requirement (internal use) | **Free and clear** |
| Non-profit | Share if offering as service | Usually fine |

---

## Why AGPL for Harnesses & SDKs

### What AGPL Does

The Affero GPL requires that if you modify and offer the software as a network service, you must provide the source code to users of that service.

### Contributor Ecosystem

AGPL encourages:
- Bug fixes flow back to project
- Feature improvements shared
- Forks must remain open
- Community builds together

### Contributor Agreement

For accepting contributions, use a Contributor License Agreement (CLA) that:
1. Grants project maintainers flexibility to relicense
2. Ensures contributors own their work
3. Allows dual-licensing for enterprise customers

---

## Revenue Model

| Source | Description |
|--------|-------------|
| **Enterprise License** | AGPL exemption for companies wanting proprietary modifications |
| **Support Contracts** | SLA-backed support for production deployments |
| **Managed Service** | Hosted Continuum infrastructure (you're the provider, so no SSPL issue) |
| **Training & Consulting** | Implementation assistance |
| **Certification** | "Continuum Certified" program |

---

## What This Prevents

### ❌ AWS/Azure/GCP offering "Continuum DNS as a Service"
They would need to open source their entire cloud infrastructure.

### ❌ Big tech forking and making proprietary version
SSPL/AGPL both require source sharing.

### ❌ "Open core" exploitation
No premium features held back - everything is open, just with strong copyleft.

---

## What This Allows

### ✅ Individual developer using for personal projects
No restrictions beyond keeping source available.

### ✅ Startup building commercial product with Continuum
Fine, as long as they share their modifications.

### ✅ Enterprise self-hosting
Internal use has no service requirement.

### ✅ Academic/Research use
Fully permitted.

### ✅ Contributing back improvements
Welcomed and encouraged via CLA.

---

## Legal Considerations

**Note**: This document represents strategic intent. Before finalizing:

1. **Consult IP attorney** - Ensure licenses are correctly applied
2. **Review CLA language** - Standard CLAs (Apache, Django) as templates
3. **Consider trademark protection** - "Continuum" name registration
4. **Document compliance** - Clear guidelines for users

---

## Implementation

### Repository Headers

```
// SPDX-License-Identifier: SSPL-1.0

/*
 * Continuum DNS - Service Discovery for AI Orchestration
 * Copyright (C) 2026 [Your Name/Entity]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Server Side Public License for more details.
 */
```

### README Badges

```markdown
[![License: SSPL](https://img.shields.io/badge/License-SSPL%201.0-blue.svg)](https://www.mongodb.com/licensing/server-side-public-license)

## License

This project is licensed under the Server Side Public License (SSPL).

**What this means for you:**
- ✅ Free to use, modify, and distribute
- ✅ Free for personal, internal, and commercial use
- ⚠️ If you offer this as a service, you must open source ALL supporting software

See [LICENSE](LICENSE) for full terms.
```

---

## FAQ

**Q: Can I use Continuum DNS in my startup's product?**  
A: Yes! As long as you're not offering Continuum as a hosted service to others, you're fine.

**Q: I want to run Continuum for my company internally. Is that okay?**  
A: Absolutely. Internal use doesn't trigger the service provision clause.

**Q: I'm building a SaaS and want to use Continuum. What do I need to do?**  
A: If Continuum is just a component of your SaaS (not the main offering), you likely just need to share your Continuum modifications. If you're offering "Continuum as a Service" specifically, you'd need to open source all supporting infrastructure.

**Q: Can Amazon offer "Continuum DNS" as an AWS service?**  
A: Only if they open source all of AWS. (They won't.)

**Q: I'm an independent developer. Is there any cost?**  
A: No. Use it freely. We want to empower you.

---

## Summary

| Goal | How License Achieves It |
|------|-------------------------|
| Empower indie devs | Free to use, strong community |
| Block big tech exploitation | SSPL forces full infrastructure disclosure |
| Enable sustainability | Enterprise licensing, support contracts |
| Build community | AGPL encourages contribution |
| Maintain control | CLA allows strategic flexibility |

---

*This licensing strategy reflects the project's commitment to democratizing AI infrastructure while ensuring sustainable development.*

