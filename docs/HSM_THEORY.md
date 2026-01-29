# Hardware Security Module Theory: Key Protection for Continuum Studio

> **Document Purpose**: Theorize HSM usage for root CA protection and key management  
> **Date**: January 2026  
> **Status**: Architectural Analysis / Future Planning

---

## Executive Summary

| Aspect | Recommendation |
|--------|---------------|
| **Homelab (Phase 1)** | Software keys with strong protection (sops-nix, Age encryption) |
| **Team (Phase 2)** | Cloud HSM (AWS CloudHSM, Azure Dedicated HSM) or managed KMS |
| **Enterprise (Phase 3)** | On-premise HSM (YubiHSM 2, Nitrokey HSM 2, or enterprise) |
| **Key Operations** | Sign-only pattern - private keys never leave HSM |

**Core Principle**: The private key for your root CA should be the most protected secret in your infrastructure. HSMs provide that protection by ensuring private keys are never exposed to software.

---

## Part 1: What is an HSM?

### Definition

A Hardware Security Module (HSM) is a dedicated cryptographic processor that:

1. **Generates** cryptographic keys within tamper-resistant hardware
2. **Stores** keys securely - private keys never leave the device
3. **Performs** cryptographic operations (signing, decryption) internally
4. **Protects** against physical and logical attacks

```
┌─────────────────────────────────────────────────────────────────┐
│                    HSM vs Software Keys                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Software Keys (Traditional)                                   │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                                                        │    │
│   │   Application ──► OS ──► Memory ──► Disk              │    │
│   │        │                    │           │              │    │
│   │        │         Private key in RAM     │              │    │
│   │        │         (extractable!)         │              │    │
│   │        │                                │              │    │
│   │        └── Key file on disk ────────────┘              │    │
│   │            (encrypted, but still                       │    │
│   │             software-accessible)                       │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                                                                 │
│   HSM (Hardware Protected)                                      │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                                                        │    │
│   │   Application                                          │    │
│   │        │                                               │    │
│   │        ▼                                               │    │
│   │   ┌─────────────────────────────────────────────────┐ │    │
│   │   │               HSM Device                         │ │    │
│   │   │  ┌─────────────────────────────────────────┐   │ │    │
│   │   │  │         Private Key                      │   │ │    │
│   │   │  │    (NEVER leaves this boundary)          │   │ │    │
│   │   │  └─────────────────────────────────────────┘   │ │    │
│   │   │                                                 │ │    │
│   │   │  App sends: "Sign this data"                    │ │    │
│   │   │  HSM returns: signature                         │ │    │
│   │   │  (key never exposed)                            │ │    │
│   │   │                                                 │ │    │
│   │   └─────────────────────────────────────────────────┘ │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Why HSMs Matter for Continuum Studio

| Scenario | Risk Without HSM | Mitigation With HSM |
|----------|------------------|---------------------|
| **Root CA compromise** | Attacker can issue any certificate, impersonate any service | Key never extractable, audit logging |
| **Memory dump attack** | Keys in RAM can be extracted | Keys never in host RAM |
| **Insider threat** | Admin can copy keys | Hardware prevents extraction |
| **Compliance** | May fail audit requirements | FIPS 140-2/3 certified |
| **Backup concerns** | Key backup is a liability | Secure backup mechanisms built-in |

---

## Part 2: HSM Options for Different Scales

### Tier 1: Homelab / Personal (Cost: $0-$100)

For personal homelab use, dedicated HSM hardware may be overkill. Instead:

**Option A: Software-based Protection (Current)**

```nix
# sops-nix for secrets management
sops.secrets.ca-private-key = {
  sopsFile = ./secrets/ca.yaml;
  owner = "root";
  group = "root";
  mode = "0400";
};
```

Pros:
- Zero cost
- Declarative, reproducible
- Integrates with NixOS

Cons:
- Key exists in RAM when used
- Encrypted file on disk is still extractable

**Option B: YubiKey as Mini-HSM (~$50-70)**

```
┌─────────────────────────────────────────────────────────────────┐
│                    YubiKey 5 for PIV/PKCS#11                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   • Generates keys on-device (RSA 2048/4096, ECC P-256/384)    │
│   • Keys never leave the YubiKey                                │
│   • PIV (Personal Identity Verification) support                │
│   • PKCS#11 interface for standard tooling                      │
│   • Touch-to-sign option for additional security                │
│                                                                 │
│   Use cases:                                                    │
│   • SSH key storage                                             │
│   • GPG signing key                                             │
│   • Small-scale CA operations (occasional signing)              │
│                                                                 │
│   Limitations:                                                  │
│   • Single user/device (not networked)                          │
│   • Limited key slots (4 PIV slots)                             │
│   • No backup without exporting private key first               │
│   • Slow for high-volume operations                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**PKCS#11 Integration Example:**

```bash
# Generate CA key on YubiKey
pkcs11-tool --module /usr/lib/opensc-pkcs11.so \
  --login --keypairgen --key-type EC:prime256v1 \
  --id 02 --label "Continuum Root CA"

# Sign certificate using YubiKey (key never leaves device)
openssl ca -engine pkcs11 -keyform engine \
  -keyfile "pkcs11:id=%02;type=private" \
  -cert ca.crt -in server.csr -out server.crt
```

### Tier 2: Small Team / Homelab+ (Cost: $100-$500)

**Option A: YubiHSM 2 (~$650)**

```
┌─────────────────────────────────────────────────────────────────┐
│                         YubiHSM 2                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Form factor: USB-A (nano size!)                               │
│   Algorithms: RSA, ECC, Ed25519, AES, HMAC                      │
│   Key storage: 128 keys                                         │
│   Interface: PKCS#11, native YubiHSM API                        │
│   Certification: FIPS 140-2 Level 3 (physical tamper evidence)  │
│                                                                 │
│   Features:                                                     │
│   • Network-shareable via yubihsm-connector                     │
│   • Audit logging                                               │
│   • Secure backup/restore (M of N key wrapping)                 │
│   • Multiple authentication credentials                         │
│                                                                 │
│   Ideal for:                                                    │
│   • Small team PKI                                              │
│   • SPIRE CA backend                                            │
│   • Code signing                                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**NixOS Integration:**

```nix
# /etc/nixos/yubihsm.nix
{ config, pkgs, ... }:

{
  # Install YubiHSM tools
  environment.systemPackages = with pkgs; [
    yubihsm-shell
    yubihsm-connector
  ];

  # Run YubiHSM connector as a service
  systemd.services.yubihsm-connector = {
    description = "YubiHSM Connector";
    wantedBy = [ "multi-user.target" ];
    after = [ "network.target" ];
    
    serviceConfig = {
      ExecStart = "${pkgs.yubihsm-connector}/bin/yubihsm-connector";
      User = "yubihsm";
      Group = "yubihsm";
      # Only listen on localhost by default
      # For network access, configure with care
    };
  };

  # Udev rules for YubiHSM device
  services.udev.extraRules = ''
    SUBSYSTEM=="usb", ATTR{idVendor}=="1050", ATTR{idProduct}=="0030", MODE="0660", GROUP="yubihsm"
  '';
}
```

**Option B: Nitrokey HSM 2 (~$79)**

```
┌─────────────────────────────────────────────────────────────────┐
│                       Nitrokey HSM 2                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Form factor: USB-A                                            │
│   Algorithms: RSA 2048/4096, ECC                                │
│   Key storage: 38 keys                                          │
│   Interface: PKCS#11 (OpenSC)                                   │
│   Open source: SmartCard-HSM firmware                           │
│                                                                 │
│   Features:                                                     │
│   • M of N key backup scheme                                    │
│   • Transport PIN for secure initialization                     │
│   • No network sharing (single host)                            │
│                                                                 │
│   Pros:                                                         │
│   • Much cheaper than YubiHSM 2                                 │
│   • Open source firmware                                        │
│   • PKCS#11 standard interface                                  │
│                                                                 │
│   Cons:                                                         │
│   • No network sharing                                          │
│   • Fewer keys than YubiHSM 2                                   │
│   • Slower                                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Tier 3: Cloud / Managed HSM (Cost: $1-5/key/month + operations)

For teams that need HSM but don't want physical hardware:

**AWS CloudHSM**

```
┌─────────────────────────────────────────────────────────────────┐
│                       AWS CloudHSM                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Cost: ~$1.60/hour per HSM (~$1,150/month minimum)             │
│                                                                 │
│   Features:                                                     │
│   • FIPS 140-2 Level 3 certified                                │
│   • Dedicated hardware in your VPC                              │
│   • PKCS#11, JCE, OpenSSL support                               │
│   • Automatic backups                                           │
│   • Multi-AZ clustering                                         │
│                                                                 │
│   Use case:                                                     │
│   • Production CA for enterprise deployment                     │
│   • Compliance-sensitive workloads                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**AWS KMS (Simpler, Cheaper)**

```
┌─────────────────────────────────────────────────────────────────┐
│                         AWS KMS                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Cost: $1/month per key + $0.03 per 10,000 operations          │
│                                                                 │
│   Features:                                                     │
│   • HSM-backed (multi-tenant, but isolated)                     │
│   • Automatic key rotation                                      │
│   • IAM integration                                             │
│   • CloudTrail audit logging                                    │
│   • Asymmetric keys supported (RSA, ECC)                        │
│                                                                 │
│   Use case:                                                     │
│   • Cost-effective CA operations                                │
│   • When FIPS 140-2 Level 2 is sufficient                       │
│   • AWS-integrated deployments                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Tier 4: Enterprise On-Premise (Cost: $10,000+)

**Thales Luna Network HSM**

```
Features:
• FIPS 140-2 Level 3
• Network-attached (multiple servers)
• High performance (thousands of operations/sec)
• Partition isolation for multi-tenancy
• Automated backup and failover

Cost: $10,000-$50,000+ depending on configuration
```

---

## Part 3: HSM Integration with SPIRE

SPIRE (the SPIFFE Runtime Environment) can use HSMs as its CA backend:

```
┌─────────────────────────────────────────────────────────────────┐
│                    SPIRE + HSM Architecture                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                    SPIRE Server                        │    │
│   │                                                        │    │
│   │  ┌─────────────────┐    ┌─────────────────────────┐   │    │
│   │  │   CA Manager    │───►│   HSM Plugin            │   │    │
│   │  │                 │    │                         │   │    │
│   │  │  - SVID signing │    │  - PKCS#11 interface    │   │    │
│   │  │  - Cert renewal │    │  - Key handle (not key) │   │    │
│   │  │                 │    │                         │   │    │
│   │  └─────────────────┘    └───────────┬─────────────┘   │    │
│   │                                     │                  │    │
│   └─────────────────────────────────────┼──────────────────┘    │
│                                         │                       │
│                                         ▼                       │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                    YubiHSM 2                           │    │
│   │                                                        │    │
│   │   ┌─────────────────────────────────────────────────┐ │    │
│   │   │  Root CA Private Key (Ed25519/ECDSA)            │ │    │
│   │   │                                                  │ │    │
│   │   │  Key ID: 0x0001                                  │ │    │
│   │   │  Operations: Sign only                           │ │    │
│   │   │  Never exported                                  │ │    │
│   │   └─────────────────────────────────────────────────┘ │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                                                                 │
│   Flow:                                                         │
│   1. Workload requests SVID from SPIRE Agent                   │
│   2. Agent forwards to SPIRE Server                            │
│   3. Server's CA Manager invokes HSM Plugin                    │
│   4. Plugin sends signing request to HSM                       │
│   5. HSM signs CSR, returns signature                          │
│   6. Server assembles certificate                              │
│   7. SVID returned to workload                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### SPIRE HSM Plugin Configuration

```hcl
# spire-server.hcl
server {
  trust_domain = "continuum.studio"
  
  ca_key_type = "ec-p256"
  ca_ttl = "24h"
}

plugins {
  KeyManager "aws_kms" {
    plugin_data {
      region = "us-west-2"
      key_id = "arn:aws:kms:us-west-2:123456789:key/12345678-1234-..."
    }
  }
  
  # Or for YubiHSM:
  # KeyManager "yubihsm" {
  #   plugin_data {
  #     connector_url = "http://localhost:12345"
  #     auth_key_id = 1
  #     password_env = "YUBIHSM_PASSWORD"
  #   }
  # }
}
```

---

## Part 4: Key Ceremonies and Lifecycle

### Root CA Key Generation Ceremony

For high-security deployments, root CA key generation should follow a formal ceremony:

```
┌─────────────────────────────────────────────────────────────────┐
│                   Root CA Key Ceremony                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Participants:                                                 │
│   • Key Custodian 1 (KC1) - holds backup share 1               │
│   • Key Custodian 2 (KC2) - holds backup share 2               │
│   • Key Custodian 3 (KC3) - holds backup share 3               │
│   • Ceremony Administrator - coordinates process                │
│   • Witness - independent observer                              │
│                                                                 │
│   Steps:                                                        │
│                                                                 │
│   1. PREPARATION                                                │
│      • Air-gapped computer (no network, freshly installed)      │
│      • HSM device (factory reset, verified authentic)           │
│      • Tamper-evident bags for backup materials                 │
│      • Video recording of ceremony                              │
│                                                                 │
│   2. HSM INITIALIZATION                                         │
│      • Initialize HSM with random device auth key               │
│      • Create wrap key for backup (M of N: 2 of 3)              │
│      • Each custodian receives their share in sealed envelope   │
│                                                                 │
│   3. KEY GENERATION                                             │
│      • Generate root CA key pair inside HSM                     │
│      • Ed25519 or ECDSA P-384 recommended                       │
│      • Set key policy: sign-only, no export                     │
│                                                                 │
│   4. SELF-SIGNED ROOT CERTIFICATE                               │
│      • Create root CA certificate                               │
│      • Long validity (10-20 years)                              │
│      • Export public certificate (not private key!)             │
│                                                                 │
│   5. BACKUP                                                     │
│      • HSM creates encrypted backup of key material             │
│      • Backup requires 2 of 3 custodian shares to restore       │
│      • Store backup in separate physical locations              │
│                                                                 │
│   6. VERIFICATION                                               │
│      • Verify certificate can be used to sign                   │
│      • Verify backup shares work (test restore to second HSM)   │
│      • Document all serial numbers, hashes, participants        │
│                                                                 │
│   7. SECURE STORAGE                                             │
│      • HSM stored in secure location (safe, limited access)     │
│      • Backup shares distributed to custodians                  │
│      • Video recording archived                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Key Lifecycle for Continuum Studio

```
┌─────────────────────────────────────────────────────────────────┐
│                    Key Hierarchy & Lifecycle                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Level 0: Root CA (HSM-protected)                              │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  • Generated once, protected forever                     │  │
│   │  • Validity: 20 years                                    │  │
│   │  • Used only to sign Level 1 certificates                │  │
│   │  • Offline except for signing ceremonies                 │  │
│   │  • Backup: M of N custodian shares                       │  │
│   └─────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│   Level 1: Intermediate CA (HSM or software)                    │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  • SPIRE Server CA                                       │  │
│   │  • Validity: 2-5 years                                   │  │
│   │  • Used to sign workload certificates                    │  │
│   │  • Online, but highly protected                          │  │
│   │  • Can use HSM or software key (risk tradeoff)           │  │
│   └─────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│   Level 2: Workload Certificates (software, short-lived)        │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  • X.509-SVIDs for services                              │  │
│   │  • Validity: 1 hour (SPIRE default)                      │  │
│   │  • Automatically rotated                                 │  │
│   │  • Generated in software (acceptable risk)               │  │
│   │  • No backup needed (ephemeral)                          │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 5: Practical HSM Scenarios for Continuum

### Scenario A: Personal Homelab (No HSM)

```
Trust Model:
• Single user, trusted hardware
• Software-based CA (sops-nix encrypted)
• Short-lived certificates mitigate key compromise

Risk acceptance:
• Root CA key compromise = regenerate everything
• Acceptable for personal use
• Low attack surface (not exposed to internet)
```

**Implementation:**

```elixir
defmodule Continuum.CA.Software do
  @moduledoc """
  Software-based CA for homelab deployments.
  Keys stored encrypted on disk via sops-nix.
  """
  
  def sign_certificate(csr, ca_key_path) do
    # Key loaded into memory for signing
    # Risk: key in RAM during operation
    # Mitigation: short operation time, memory cleared after
    
    ca_key = load_encrypted_key(ca_key_path)
    cert = X509.sign(csr, ca_key, validity: {1, :hour})
    :crypto.strong_rand_bytes(32) |> then(fn _ -> :ok end)  # Overwrite key memory
    cert
  end
end
```

### Scenario B: Shared Homelab (YubiKey)

```
Trust Model:
• Multiple users, some shared hardware
• YubiKey holds root CA key
• Touch-to-sign for CA operations

Benefits:
• Key never extractable
• Physical presence required
• Audit via YubiKey logs

Limitations:
• Manual process for CA signing
• Single point of hardware failure
```

**Implementation:**

```elixir
defmodule Continuum.CA.YubiKey do
  @moduledoc """
  YubiKey-backed CA using PIV/PKCS#11.
  """
  
  @pkcs11_module "/usr/lib/opensc-pkcs11.so"
  
  def sign_certificate(csr) do
    # Sign using PKCS#11 - key never leaves YubiKey
    System.cmd("openssl", [
      "ca",
      "-engine", "pkcs11",
      "-keyform", "engine", 
      "-keyfile", "pkcs11:id=%02;type=private",
      "-in", csr_path,
      "-out", "-"
    ])
  end
end
```

### Scenario C: Team Deployment (YubiHSM 2)

```
Trust Model:
• Team of developers
• YubiHSM 2 on dedicated server
• SPIRE uses HSM for CA operations
• Network-accessible via yubihsm-connector

Benefits:
• Automated certificate issuance
• Centralized key management
• Audit logging built-in
• Secure backup with M of N

Cost: ~$650 one-time + server
```

**SPIRE + YubiHSM Configuration:**

```hcl
# spire-server.hcl for YubiHSM
plugins {
  KeyManager "yubihsm" {
    plugin_data {
      connector_url = "http://hsm-server.internal:12345"
      auth_key_id = 1
      password_env = "YUBIHSM_AUTH_PASSWORD"
      key_id = 1  # Pre-generated on HSM
    }
  }
}
```

### Scenario D: Enterprise/Cloud (AWS KMS)

```
Trust Model:
• Compliance requirements (FIPS, SOC2, etc.)
• Multi-region deployment
• Automated everything

Benefits:
• No hardware to manage
• Automatic key rotation
• IAM-based access control
• CloudTrail audit logs
• High availability

Cost: ~$1/month/key + operations
```

**SPIRE + AWS KMS Configuration:**

```hcl
# spire-server.hcl for AWS KMS
plugins {
  KeyManager "aws_kms" {
    plugin_data {
      region = "us-west-2"
      key_id = "alias/continuum-spire-ca"
      access_key_id = "" # Use IAM role instead
      secret_access_key = ""
    }
  }
}
```

---

## Part 6: NixOS HSM Integration Patterns

### Pattern 1: YubiHSM Service

```nix
# modules/yubihsm.nix
{ config, lib, pkgs, ... }:

with lib;

let
  cfg = config.services.yubihsm;
in {
  options.services.yubihsm = {
    enable = mkEnableOption "YubiHSM connector service";
    
    listenAddress = mkOption {
      type = types.str;
      default = "127.0.0.1";
      description = "Address to listen on";
    };
    
    port = mkOption {
      type = types.port;
      default = 12345;
      description = "Port for connector";
    };
  };

  config = mkIf cfg.enable {
    environment.systemPackages = with pkgs; [
      yubihsm-shell
      yubihsm-connector
    ];

    users.users.yubihsm = {
      isSystemUser = true;
      group = "yubihsm";
      description = "YubiHSM service user";
    };
    users.groups.yubihsm = {};

    services.udev.extraRules = ''
      # YubiHSM 2
      SUBSYSTEM=="usb", ATTR{idVendor}=="1050", ATTR{idProduct}=="0030", \
        MODE="0660", GROUP="yubihsm", TAG+="uaccess"
    '';

    systemd.services.yubihsm-connector = {
      description = "YubiHSM Connector";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" "systemd-udevd.service" ];
      
      serviceConfig = {
        Type = "simple";
        User = "yubihsm";
        Group = "yubihsm";
        ExecStart = ''
          ${pkgs.yubihsm-connector}/bin/yubihsm-connector \
            --listen ${cfg.listenAddress}:${toString cfg.port}
        '';
        Restart = "always";
        RestartSec = 5;
        
        # Security hardening
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
      };
    };
  };
}
```

### Pattern 2: SPIRE with HSM Backend

```nix
# modules/spire-hsm.nix
{ config, lib, pkgs, ... }:

let
  cfg = config.services.spire;
  
  serverConfig = pkgs.writeText "spire-server.hcl" ''
    server {
      trust_domain = "${cfg.trustDomain}"
      bind_address = "0.0.0.0"
      bind_port = 8081
      
      ca_ttl = "24h"
      default_x509_svid_ttl = "1h"
    }

    plugins {
      KeyManager "${cfg.keyManager.type}" {
        plugin_data {
          ${cfg.keyManager.config}
        }
      }
      
      DataStore "sql" {
        plugin_data {
          database_type = "sqlite3"
          connection_string = "/var/lib/spire/datastore.sqlite3"
        }
      }
      
      NodeAttestor "join_token" {
        plugin_data {}
      }
    }
  '';
in {
  options.services.spire.keyManager = {
    type = lib.mkOption {
      type = lib.types.enum [ "memory" "disk" "aws_kms" "yubihsm" ];
      default = "disk";
    };
    
    config = lib.mkOption {
      type = lib.types.str;
      default = "";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.spire-server = {
      # ... service definition using serverConfig
    };
  };
}
```

---

## Part 7: Threat Model Analysis

### Threats HSMs Mitigate

| Threat | Without HSM | With HSM |
|--------|-------------|----------|
| **Key extraction via memory dump** | High risk | Eliminated |
| **Key theft via disk access** | Medium risk (if encrypted) | Eliminated |
| **Unauthorized signing** | Depends on access control | Hardware-enforced policy |
| **Insider key copying** | Possible | Physically impossible |
| **Cold boot attack** | Possible | Eliminated |
| **Side-channel attacks** | Possible | Hardened against |

### Threats HSMs DON'T Mitigate

| Threat | Mitigation |
|--------|------------|
| **Compromised application** | HSM signs whatever it's told to sign |
| **Physical theft of HSM** | M of N backup, remote lock/wipe |
| **Denial of service** | HSM redundancy, fallback procedures |
| **Weak key generation** | Use HSM's TRNG, not software RNG |
| **Poor certificate policies** | Enforce in signing application |

### Compensating Controls

```
If no HSM:
┌─────────────────────────────────────────────────────────────────┐
│                    Compensating Controls                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   1. Short-lived certificates (1 hour default)                  │
│      → Limits exposure window if key compromised                │
│                                                                 │
│   2. Encrypted key storage (sops-nix)                           │
│      → Defense in depth for key at rest                         │
│                                                                 │
│   3. Memory-safe language (Rust, Elixir)                        │
│      → Reduces memory disclosure bugs                           │
│                                                                 │
│   4. Minimal key exposure                                       │
│      → Only load key for signing operation                      │
│      → Clear memory immediately after                           │
│                                                                 │
│   5. Certificate Transparency logs                              │
│      → Detect unauthorized certificate issuance                 │
│                                                                 │
│   6. Monitoring and alerting                                    │
│      → Detect anomalous signing activity                        │
│                                                                 │
│   7. Easy revocation and rotation                               │
│      → Can quickly recover from compromise                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 8: Recommendations for Continuum Studio

### Phased Approach

| Phase | Deployment | Key Protection | Cost |
|-------|------------|----------------|------|
| **Phase 1** | Personal homelab | sops-nix + short-lived certs | $0 |
| **Phase 2** | Shared/Team | YubiHSM 2 | ~$650 |
| **Phase 3** | Enterprise | Cloud HSM or on-prem | $1K-10K+/year |

### Architecture Decision

```
┌─────────────────────────────────────────────────────────────────┐
│               Recommended Key Architecture                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Root CA Key:                                                  │
│   • HSM when available/affordable                               │
│   • Software with sops-nix for homelab                          │
│   • Offline/air-gapped for highest security                     │
│                                                                 │
│   Intermediate CA Key (SPIRE):                                  │
│   • HSM strongly recommended for teams                          │
│   • Software acceptable for personal use                        │
│   • Online, automated operations                                │
│                                                                 │
│   Workload Keys:                                                │
│   • Always software (ephemeral, auto-rotated)                   │
│   • Generated by SPIRE Agent                                    │
│   • No backup needed                                            │
│                                                                 │
│   Other Secrets (API keys, tokens):                             │
│   • sops-nix for static secrets                                 │
│   • Vault for dynamic secrets                                   │
│   • HSM optional for encryption keys                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Implementation Checklist

**For Homelab (Phase 1):**

- [ ] Use sops-nix for CA private key encryption
- [ ] Configure SPIRE with short-lived certificates (1h)
- [ ] Enable audit logging for all CA operations
- [ ] Document recovery procedure for key loss
- [ ] Test backup/restore process

**For Team (Phase 2):**

- [ ] Acquire YubiHSM 2 (or similar)
- [ ] Perform key ceremony with witnesses
- [ ] Configure M of N backup (2 of 3 recommended)
- [ ] Integrate with SPIRE KeyManager plugin
- [ ] Set up monitoring for HSM health
- [ ] Document operational procedures

**For Enterprise (Phase 3):**

- [ ] Evaluate cloud HSM vs on-premise
- [ ] Design multi-region key hierarchy
- [ ] Implement Certificate Transparency logging
- [ ] Create formal key management policy
- [ ] Schedule regular key ceremony audits
- [ ] Plan disaster recovery for HSM failure

---

## Summary

| Question | Answer |
|----------|--------|
| **Do I need an HSM for personal use?** | No, but it's nice to have (YubiKey) |
| **When should I get a real HSM?** | When sharing infrastructure with others |
| **What's the minimum viable HSM?** | YubiHSM 2 (~$650) or Nitrokey HSM 2 (~$79) |
| **Can I use cloud HSM?** | Yes, AWS KMS is cost-effective for most uses |
| **What if I can't afford HSM?** | Use short-lived certs + sops-nix + audit logging |

**Key Takeaway**: HSMs are about **risk reduction**, not risk elimination. For personal/homelab use, software-based keys with good operational practices are acceptable. As you scale to team or enterprise, HSMs become increasingly important for compliance and security posture.

---

*Document created as architectural analysis for Continuum Studio key management requirements.*

