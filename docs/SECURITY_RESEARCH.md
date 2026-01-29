# Security Research for Continuum Studio

**Document Purpose**: Deep-dive research into security options beyond Erlang's cookie-based authentication for the distributed Continuum Studio architecture.

**Date**: January 2026

---

## Part 1: Understanding the Security Landscape

### Current State: BEAM Magic Cookies

Erlang/Elixir's default security model uses **magic cookies** for node authentication:

```erlang
% Cookie-based authentication flow
1. Node A wants to connect to Node B
2. Both nodes exchange hashed challenges (not the actual cookie)
3. If cookies match, connection is established
4. All subsequent communication is in CLEAR TEXT by default
```

**Critical Limitations**:

- ❌ **Not cryptographically secure** - only prevents accidental misuse
- ❌ **No encryption** - communication is plaintext
- ❌ **No fine-grained access control** - all-or-nothing trust
- ❌ **No identity verification** - just shared secret
- ❌ **Static credentials** - cookie doesn't rotate automatically
- ❌ **Network exposure** - any network attacker can intercept traffic

**When Cookies Are Acceptable**:

- Development environments
- Trusted internal networks (with network-level isolation)
- Single-machine deployments

---

## Part 2: TLS for Erlang Distribution

### Overview

Erlang/OTP supports **TLS-encrypted distribution** via the `inet_tls_dist` module, replacing plaintext TCP with encrypted channels.

### Configuration

```erlang
% Start node with TLS distribution
erl -proto_dist inet_tls \
    -ssl_dist_optfile /path/to/ssl_dist.conf \
    -sname my_node

% ssl_dist.conf example
[{server,
  [{certfile, "/path/to/server.pem"},
   {keyfile, "/path/to/server-key.pem"},
   {cacertfile, "/path/to/ca.pem"},
   {verify, verify_peer},
   {fail_if_no_peer_cert, true}]},
 {client,
  [{certfile, "/path/to/client.pem"},
   {keyfile, "/path/to/client-key.pem"},
   {cacertfile, "/path/to/ca.pem"},
   {verify, verify_peer}]}].
```

### Benefits

| Benefit | Description |
|---------|-------------|
| **Encryption** | All distribution traffic encrypted with TLS |
| **Server Authentication** | Clients verify server certificate |
| **Client Authentication** | Servers verify client certificate (mTLS) |
| **Integrity** | Tampering detected |
| **PKI Integration** | Works with existing certificate infrastructure |

### Considerations

- **Certificate Management**: Need to issue, rotate, and revoke certificates
- **Performance**: Small overhead from encryption (~5-10%)
- **IPv6 Support**: Use `-proto_dist inet6_tls` for IPv6
- **Still Uses Cookies**: TLS adds encryption, but cookies still used for BEAM handshake

### Recommendation for Continuum Studio

**TLS distribution should be the MINIMUM security baseline** for any multi-machine deployment:

```
Priority: HIGH
Complexity: MEDIUM
Implementation: Phase 1 (Foundation)
```

---

## Part 3: Mutual TLS (mTLS)

### What is mTLS?

Mutual TLS extends standard TLS by requiring **both parties** to present and verify certificates:

```
Standard TLS:
Client → Server: "Prove you're who you claim to be"
Server → Client: [Certificate]
Client: [Verifies certificate]

Mutual TLS:
Client → Server: "Prove you're who you claim to be"
Server → Client: [Certificate]
Client: [Verifies certificate]
Server → Client: "Now you prove who YOU are"
Client → Server: [Certificate]
Server: [Verifies certificate]
```

### mTLS Benefits

| Attack Type | Protection Level |
|-------------|------------------|
| **On-path attacks** | ✅ MITM impossible - attackers can't present valid certs |
| **Spoofing** | ✅ Identity verified cryptographically |
| **Credential stuffing** | ✅ Stolen passwords useless without certificate |
| **Brute force** | ✅ Certificates required, not just passwords |
| **Phishing** | ✅ Even stolen creds need valid cert |
| **Malicious API requests** | ✅ Only authenticated clients can make requests |

### mTLS in Zero Trust Architecture

mTLS is fundamental to Zero Trust:

- **Never trust, always verify** - every connection authenticated
- **Workload identity** - services have cryptographic identity
- **Microsegmentation** - fine-grained access based on identity

### Implementation for Continuum Studio

```nix
# Example NixOS mTLS configuration
services.continuum-studio = {
  security = {
    tls = {
      enable = true;
      mutualTLS = true;
      serverCert = "/etc/continuum/certs/server.pem";
      serverKey = "/etc/continuum/certs/server-key.pem";
      clientCA = "/etc/continuum/certs/client-ca.pem";
      
      # Certificate requirements
      minTLSVersion = "1.3";
      cipherSuites = [
        "TLS_AES_256_GCM_SHA384"
        "TLS_CHACHA20_POLY1305_SHA256"
      ];
    };
  };
};
```

### Certificate Authority Strategy

For Continuum Studio, we need a **private CA** for issuing workload certificates:

**Option A: Self-Managed CA**

```
Pros: Full control, no external dependencies
Cons: Complexity, must handle rotation/revocation

Structure:
Root CA (offline, HSM-protected)
└── Intermediate CA (online, issues workload certs)
    ├── Studio Core Certificate
    ├── Synapsix Harness Certificate
    ├── Dialog Daemon Certificate
    └── Agent Bridge Certificate
```

**Option B: SPIFFE/SPIRE (Recommended)**

- Automatic certificate issuance and rotation
- Workload attestation (prove identity via platform)
- Federation support
- Industry standard

```
Priority: HIGH
Complexity: MEDIUM-HIGH
Implementation: Phase 1 (Foundation)
```

---

## Part 4: JSON Web Tokens (JWT)

### Overview

JWTs are compact, URL-safe tokens for transmitting claims between parties:

```
Structure: HEADER.PAYLOAD.SIGNATURE

Header:
{
  "alg": "RS256",
  "typ": "JWT"
}

Payload:
{
  "sub": "synapsix-harness-cursor",
  "iss": "continuum-studio-auth",
  "aud": "studio-core",
  "exp": 1706500000,
  "iat": 1706499700,
  "permissions": ["harness:control", "dialog:send"]
}

Signature: RS256(base64(header) + "." + base64(payload), private_key)
```

### JWT vs Cookies for BEAM

| Aspect | Magic Cookie | JWT |
|--------|--------------|-----|
| **Transport** | BEAM distribution | HTTP headers, gRPC metadata |
| **Scope** | Node-to-node | Any protocol |
| **Claims** | None | Rich metadata |
| **Expiration** | None | Built-in `exp` claim |
| **Rotation** | Manual | Automatic via refresh tokens |
| **Revocation** | Stop node | Token blacklist or short expiry |

### Use Cases in Continuum Studio

**1. API Authentication**

```elixir
# Agent Bridge authenticating to Studio Core
defmodule ContinuumStudio.Auth.JWT do
  def verify_agent_token(token) do
    case Joken.verify(token, signer()) do
      {:ok, claims} ->
        if claims["aud"] == "studio-core" do
          {:ok, claims}
        else
          {:error, :invalid_audience}
        end
      {:error, reason} -> {:error, reason}
    end
  end
end
```

**2. Short-Lived Session Tokens**

```elixir
# Issue token for agent session (5 minute validity)
def issue_session_token(agent_id, permissions) do
  claims = %{
    "sub" => agent_id,
    "iss" => "continuum-studio",
    "permissions" => permissions,
    "exp" => DateTime.utc_now() |> DateTime.add(300, :second) |> DateTime.to_unix()
  }
  Joken.generate_and_sign(claims, signer())
end
```

**3. Signed Commands**

```elixir
# Harness commands signed with JWT to prove origin
defmodule Synapsix.Command do
  def execute(harness, command, jwt) do
    with {:ok, claims} <- verify_jwt(jwt),
         :ok <- check_permission(claims, :harness_control) do
      Synapsix.Harness.execute(harness, command)
    end
  end
end
```

### JWT Best Practices

1. **Use asymmetric signing (RS256, ES256)** - private key stays secret
2. **Short expiration times** - 5-15 minutes for access tokens
3. **Include `aud` claim** - prevent token reuse across services
4. **Rotate signing keys** - periodically update key pairs
5. **Don't store sensitive data** - JWTs are readable (base64, not encrypted)

```
Priority: MEDIUM
Complexity: LOW-MEDIUM
Implementation: Phase 2 (Service Communication)
```

---

## Part 5: SPIFFE/SPIRE - Workload Identity

### What is SPIFFE?

**SPIFFE** (Secure Production Identity Framework for Everyone) is a set of open standards for workload identity:

- **SPIFFE ID**: URI-based identity (`spiffe://trust-domain/workload-path`)
- **SVID**: Verifiable identity document (X.509 or JWT)
- **Workload API**: Automatic credential delivery and rotation
- **Federation**: Cross-domain trust

### SPIFFE Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        SPIRE Server                             │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ Registration│  │   CA (PKI)   │  │  Attestation Engine    │ │
│  │   Entries   │  │              │  │  (Node + Workload)     │ │
│  └─────────────┘  └──────────────┘  └────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SPIRE Agent                               │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ Workload    │  │ SVID Cache   │  │  Workload Attestor     │ │
│  │ API Server  │  │              │  │  (Unix, K8s, Docker)   │ │
│  └─────────────┘  └──────────────┘  └────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Your Workloads                             │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │Studio Core  │  │  Synapsix    │  │  Dialog Daemon         │ │
│  │spiffe://...│  │spiffe://...   │  │spiffe://...            │ │
│  └─────────────┘  └──────────────┘  └────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### SPIFFE IDs for Continuum Studio

```
Trust Domain: continuum.local (or your domain)

SPIFFE IDs:
spiffe://continuum.local/studio/core          # Studio Core process
spiffe://continuum.local/synapsix/harness/*   # All harnesses
spiffe://continuum.local/synapsix/dialog      # Dialog daemon
spiffe://continuum.local/bridge/cursor        # Cursor agent bridge
spiffe://continuum.local/bridge/ollama        # Local LLM bridge
spiffe://continuum.local/bridge/openai        # OpenAI API bridge
```

### Workload Attestation

SPIRE proves workload identity through **attestation** - verifying the workload is what it claims:

| Attestor | Environment | Proof |
|----------|-------------|-------|
| **Unix** | Linux/macOS | Process UID, GID, path |
| **Kubernetes** | K8s cluster | Service account, namespace, pod labels |
| **Docker** | Containers | Container ID, image hash |
| **AWS** | EC2/Lambda | Instance identity document |
| **GCP** | GCE/GKE | Instance metadata |

### Example: Synapsix Harness with SPIRE

```elixir
# lib/synapsix/spiffe_auth.ex
defmodule Synapsix.SpiffeAuth do
  @moduledoc """
  SPIFFE-based authentication for harness communication.
  """
  
  @workload_api_path "/run/spire/sockets/agent.sock"
  
  def get_svid do
    # Fetch X.509 SVID from SPIRE Agent
    case SpiffeWorkloadAPI.fetch_x509_svid(@workload_api_path) do
      {:ok, svid} -> {:ok, svid}
      {:error, reason} -> {:error, {:spiffe_fetch_failed, reason}}
    end
  end
  
  def verify_peer_identity(peer_svid, allowed_spiffe_ids) do
    spiffe_id = extract_spiffe_id(peer_svid)
    
    if spiffe_id in allowed_spiffe_ids do
      {:ok, spiffe_id}
    else
      {:error, {:unauthorized_spiffe_id, spiffe_id}}
    end
  end
  
  # Allow studio core to control harnesses
  def authorize_harness_control(peer_svid) do
    verify_peer_identity(peer_svid, [
      "spiffe://continuum.local/studio/core",
      "spiffe://continuum.local/bridge/*"
    ])
  end
end
```

### SPIFFE Federation

SPIFFE supports cross-domain trust via **federation**:

```
┌─────────────────────┐          ┌─────────────────────┐
│   continuum.local   │◄────────►│  partner.example    │
│                     │  Trust   │                     │
│  - Studio Core      │  Bundle  │  - External Agent   │
│  - Synapsix         │          │  - Partner Services │
└─────────────────────┘          └─────────────────────┘

# Federation allows workloads from partner.example to 
# authenticate to continuum.local services (and vice versa)
```

### SPIRE Benefits Summary

| Benefit | Description |
|---------|-------------|
| **Zero static credentials** | Certificates issued dynamically, rotated automatically |
| **Platform attestation** | Prove identity via environment (not passwords) |
| **Short-lived credentials** | 1-hour default TTL, auto-renewed |
| **Cross-platform** | Kubernetes, VMs, bare metal, serverless |
| **Federation** | Trust across organizational boundaries |
| **Standards-based** | Open specs, multiple implementations |

```
Priority: MEDIUM-HIGH
Complexity: HIGH
Implementation: Phase 2-3 (after TLS foundation)
```

---

## Part 6: Capability-Based Security

### What Are Capabilities?

Instead of "Who are you?" (identity-based), capabilities ask "What can you do?" (permission-based):

```
Traditional (Identity):
1. Authenticate: "I am agent-cursor"
2. Authorize: "Does agent-cursor have permission X?"
3. Execute: If authorized, perform action

Capability-based:
1. Present capability token: "I have this capability"
2. Validate capability: Is it valid and not revoked?
3. Execute: Perform the action the capability allows
```

### Macaroons

**Macaroons** are capability tokens with contextual caveats:

```
Macaroon Structure:
{
  location: "continuum-studio",
  identifier: "harness-control-v1",
  signature: HMAC(root_key, caveats),
  caveats: [
    "harness = cursor",
    "time < 2026-01-29T12:00:00Z",
    "source_ip = 192.168.1.0/24"
  ]
}
```

**Key Feature**: Caveats can be **added** (attenuated) but never removed:

```elixir
# Create a root macaroon
macaroon = Macaroon.create(
  location: "continuum-studio",
  identifier: "full-access",
  key: root_key
)

# Attenuate for specific use case
restricted = macaroon
  |> Macaroon.add_first_party_caveat("harness = cursor")
  |> Macaroon.add_first_party_caveat("action in [type, click, focus]")
  |> Macaroon.add_first_party_caveat("expires_at < #{DateTime.utc_now() |> DateTime.add(3600)}")

# Pass restricted macaroon to agent
# Agent can further restrict but NEVER expand permissions
```

### Macaroons vs JWT

| Aspect | JWT | Macaroon |
|--------|-----|----------|
| **Attenuation** | ❌ Fixed at creation | ✅ Can add caveats |
| **Third-party auth** | ❌ No | ✅ Discharge macaroons |
| **Contextual constraints** | Limited | Rich caveat language |
| **Revocation** | Token blacklist | Root key rotation |
| **Delegation** | Create new token | Attenuate existing |

### Use Case: Delegated Harness Control

```
Scenario: User grants AI agent limited harness control

1. User creates root capability for all harnesses
2. Dialog daemon asks: "Grant Cursor harness control?"
3. User approves with constraints:
   - Only Cursor harness
   - Only type and click actions
   - 1 hour expiration
   - Current session only
4. Attenuated macaroon passed to agent
5. Agent can further restrict (but not expand)
6. All harness operations require valid macaroon
```

### Implementation Sketch

```elixir
defmodule ContinuumStudio.Capabilities do
  @moduledoc """
  Capability-based authorization using macaroons.
  """
  
  def create_harness_capability(harness_id, actions, ttl_seconds) do
    Macaroon.create(
      location: "continuum-studio",
      identifier: UUID.uuid4(),
      key: get_root_key()
    )
    |> Macaroon.add_first_party_caveat("harness = #{harness_id}")
    |> Macaroon.add_first_party_caveat("actions in #{inspect(actions)}")
    |> Macaroon.add_first_party_caveat(
      "expires_at < #{DateTime.utc_now() |> DateTime.add(ttl_seconds)}"
    )
  end
  
  def verify_capability(macaroon, context) do
    Macaroon.verify(
      macaroon,
      key: get_root_key(),
      satisfiers: [
        &verify_harness(&1, context.harness_id),
        &verify_action(&1, context.action),
        &verify_expiration(&1, DateTime.utc_now())
      ]
    )
  end
end
```

```
Priority: MEDIUM
Complexity: MEDIUM
Implementation: Phase 3 (Advanced Features)
```

---

## Part 7: Security Architecture Recommendations

### Layered Security Model

```
┌─────────────────────────────────────────────────────────────────┐
│                    Layer 4: Application                         │
│                    Capabilities / Macaroons                     │
│                    Fine-grained access control                  │
├─────────────────────────────────────────────────────────────────┤
│                    Layer 3: Service Identity                    │
│                    SPIFFE/SPIRE or JWT-based                    │
│                    Service-to-service auth                      │
├─────────────────────────────────────────────────────────────────┤
│                    Layer 2: Transport                           │
│                    mTLS for all connections                     │
│                    Encryption + mutual auth                     │
├─────────────────────────────────────────────────────────────────┤
│                    Layer 1: Network                             │
│                    TLS distribution for BEAM                    │
│                    Network segmentation (Tailscale)             │
└─────────────────────────────────────────────────────────────────┘
```

### Phased Implementation

#### Phase 1: Foundation (Immediate)

| Component | Implementation |
|-----------|----------------|
| BEAM Distribution | TLS with `-proto_dist inet_tls` |
| D-Bus Communication | Unix socket permissions (existing) |
| Network Layer | Tailscale for multi-machine (already in use) |

**Deliverable**: All distribution traffic encrypted

#### Phase 2: Service Identity (Short-term)

| Component | Implementation |
|-----------|----------------|
| Service Authentication | JWT tokens for API calls |
| Certificate Management | Self-signed CA or Let's Encrypt |
| mTLS | Between Studio Core ↔ External services |

**Deliverable**: All services have cryptographic identity

#### Phase 3: Advanced Security (Medium-term)

| Component | Implementation |
|-----------|----------------|
| Workload Identity | SPIFFE/SPIRE integration |
| Fine-grained Access | Capability-based authorization |
| Audit Logging | All security events logged |
| Key Management | Hardware security module (optional) |

**Deliverable**: Zero-trust architecture with full audit trail

### Quick Reference: Security Options

| Mechanism | Best For | Complexity | Our Priority |
|-----------|----------|------------|--------------|
| TLS Distribution | BEAM node encryption | Low | **HIGH** |
| mTLS | Service-to-service auth | Medium | **HIGH** |
| JWT | API authentication | Low | **MEDIUM** |
| SPIFFE/SPIRE | Workload identity | High | **MEDIUM** |
| Macaroons | Delegated capabilities | Medium | **LOW** |
| OAuth 2.0 | User authentication | Medium | **LOW** (not primary) |

---

## Part 8: Continuum Studio Specific Recommendations

### For Synapsix (Harness System)

```elixir
# Recommended security stack for harnesses
defmodule Synapsix.Security do
  @doc """
  Security configuration for harness communication.
  """
  
  # Phase 1: TLS for all BEAM distribution
  config :synapsix, :distribution,
    protocol: :inet_tls,
    certfile: "/etc/synapsix/certs/node.pem",
    keyfile: "/etc/synapsix/certs/node-key.pem",
    cacertfile: "/etc/synapsix/certs/ca.pem",
    verify: :verify_peer
  
  # Phase 2: JWT for dialog daemon communication
  config :synapsix, :dialog_auth,
    issuer: "synapsix",
    audience: "cursor-dialog-daemon",
    algorithm: :rs256,
    ttl: 300  # 5 minutes
  
  # Phase 3: SPIFFE for multi-machine deployments
  config :synapsix, :spiffe,
    trust_domain: "continuum.local",
    workload_api: "/run/spire/sockets/agent.sock"
end
```

### For Studio Core (Rust/GUI)

```rust
// Security configuration for Studio Core
pub struct SecurityConfig {
    // Phase 1: TLS for all gRPC/HTTP
    pub tls: TlsConfig {
        cert_path: PathBuf,
        key_path: PathBuf,
        ca_path: PathBuf,
        require_client_cert: bool,
    },
    
    // Phase 2: JWT validation
    pub jwt: JwtConfig {
        public_key_path: PathBuf,
        allowed_issuers: Vec<String>,
        required_audience: String,
    },
    
    // Phase 3: SPIFFE (optional)
    pub spiffe: Option<SpiffeConfig> {
        workload_api_socket: PathBuf,
        expected_spiffe_ids: Vec<String>,
    },
}
```

### For Dialog Daemon

The dialog daemon already uses D-Bus over Unix sockets, which provides:

- ✅ Local-only access (no network exposure)
- ✅ Unix permissions (uid/gid based)
- ✅ No additional encryption needed (local IPC)

**Recommendation**: Keep current D-Bus approach for local, add JWT validation when accepting commands from BEAM nodes.

---

## Part 9: Open Questions

1. **Key Management**: Use HashiCorp Vault, SOPS, or simple file-based secrets?
2. **Certificate Rotation**: Manual, automated via SPIRE, or cert-manager?
3. **Audit Requirements**: What level of security event logging is needed?
4. **Federation Scope**: Will Continuum Studio need to trust external identity providers?
5. **HSM Usage**: Is hardware security module warranted for root CA?

---

## Summary

| Security Mechanism | Benefit | When to Implement |
|--------------------|---------|-------------------|
| **TLS Distribution** | Encrypted BEAM traffic | Immediately (Phase 1) |
| **mTLS** | Mutual authentication | Foundation (Phase 1) |
| **JWT** | Stateless API auth | Service integration (Phase 2) |
| **SPIFFE/SPIRE** | Automated workload identity | Scale-out (Phase 2-3) |
| **Capabilities** | Fine-grained delegation | Advanced features (Phase 3) |

**Bottom Line**: Start with TLS distribution and mTLS as the foundation. Add JWT for API authentication. Consider SPIFFE/SPIRE when scaling to multiple machines. Capabilities are a future enhancement for sophisticated delegation scenarios.
