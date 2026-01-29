# Federation Scenarios: Trust Relationships in Continuum Studio

> **Document Purpose**: Explore federation scenarios and trust relationship models  
> **Date**: January 2026  
> **Status**: Architectural Analysis

---

## Executive Summary

Federation in Continuum Studio addresses: "How do multiple trust domains interact securely?"

| Scenario | Complexity | Priority | Use Case |
|----------|------------|----------|----------|
| **Single User, Multi-Machine** | Low | HIGH | Personal homelab |
| **Shared Homelab** | Medium | MEDIUM | Family/roommates |
| **Team Collaboration** | Medium | MEDIUM | Small dev team |
| **Enterprise Integration** | High | LOW | Corporate environments |
| **Public Service Provider** | High | LOW | Offering harnesses as a service |

**Recommendation**: Start with single-user multi-machine (Phase 1), design for team collaboration (Phase 2), defer enterprise/public scenarios to future releases.

---

## Part 1: What is Federation?

### Definition

Federation allows **independent trust domains** to establish mutual trust and interoperate securely, without requiring a central authority that controls both domains.

```
┌─────────────────────────────────────────────────────────────────┐
│                       Without Federation                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│     Domain A                        Domain B                    │
│   ┌─────────────┐                ┌─────────────┐               │
│   │   User 1    │   ❌ Cannot    │   User 2    │               │
│   │   Synapsix  │   communicate  │   Synapsix  │               │
│   └─────────────┘                └─────────────┘               │
│                                                                 │
│   • Separate identities                                         │
│   • No shared authentication                                    │
│   • No cross-domain access                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        With Federation                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│     Domain A                        Domain B                    │
│   ┌─────────────┐     Trust      ┌─────────────┐               │
│   │   User 1    │◄──────────────►│   User 2    │               │
│   │   Synapsix  │     Bundle     │   Synapsix  │               │
│   └─────────────┘                └─────────────┘               │
│                                                                 │
│   • Cross-domain identity verification                          │
│   • Policy-controlled access                                    │
│   • Auditable interactions                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Why Federation Matters for Continuum

1. **Remote collaboration**: Pair programming across organizations
2. **Shared resources**: Access partner's GPU for AI inference
3. **Service integration**: Connect to external AI providers with verified identity
4. **Multi-tenant hosting**: Run harnesses for multiple users securely

---

## Part 2: Scenario 1 - Single User, Multi-Machine (Personal Homelab)

### Description

A single user operates multiple machines (workstation, laptop, phone, Pi server) and wants seamless access to harnesses from any device.

```
┌─────────────────────────────────────────────────────────────────┐
│               Single User Trust Domain: e421                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│   │  Obsidian   │  │ neon-laptop │  │   Phone     │           │
│   │  (primary)  │  │ (secondary) │  │  (mobile)   │           │
│   │             │  │             │  │             │           │
│   │ • Harnesses │  │ • Harnesses │  │ • Studio UI │           │
│   │ • CoreDNS   │  │ • Worker    │  │ • Control   │           │
│   │ • CA Root   │  │             │  │             │           │
│   └─────────────┘  └─────────────┘  └─────────────┘           │
│          │               │               │                     │
│          └───────────────┼───────────────┘                     │
│                          │                                      │
│                  ┌───────┴───────┐                             │
│                  │  Trust Root   │                             │
│                  │  (single CA)  │                             │
│                  └───────────────┘                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Trust Model

| Component | Trust Level | Mechanism |
|-----------|-------------|-----------|
| Primary workstation | Root of trust | Holds CA private key |
| Secondary machines | Full trust | Certificates issued by CA |
| Mobile devices | Limited trust | Short-lived tokens, specific permissions |

### Implementation

```elixir
# Single user, single trust domain
defmodule Synapsix.Trust.SingleUser do
  @moduledoc """
  Trust configuration for personal homelab scenario.
  All machines trust the same CA certificate.
  """
  
  defstruct [
    :trust_domain,     # "e421.homelab"
    :ca_certificate,   # Root CA cert (public)
    :machines          # List of trusted machine identities
  ]
  
  def verify_identity(peer_cert, trust) do
    # All peers must be signed by our CA
    case X509.verify_chain(peer_cert, [trust.ca_certificate]) do
      :valid -> {:ok, extract_identity(peer_cert)}
      {:invalid, reason} -> {:error, {:untrusted_peer, reason}}
    end
  end
end
```

### Security Considerations

- **Single point of compromise**: CA private key must be protected
- **Revocation**: Need CRL or OCSP for compromised devices
- **Mobile device loss**: Ability to revoke phone access quickly

### Recommended Approach

1. **SPIFFE/SPIRE** for automatic cert issuance
2. **Short-lived certificates** (1 hour TTL) for mobile
3. **Longer-lived certificates** (30 days) for workstations
4. **Cookie + mTLS** for BEAM distribution (hybrid)

---

## Part 3: Scenario 2 - Shared Homelab (Family/Roommates)

### Description

Multiple users share the same physical network but want **isolated trust domains** with optional sharing.

```
┌─────────────────────────────────────────────────────────────────┐
│                      Shared Homelab Network                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌────────────────────────┐  ┌────────────────────────┐       │
│   │  Trust Domain: e421    │  │  Trust Domain: partner │       │
│   │                        │  │                        │       │
│   │  ┌──────────────────┐  │  │  ┌──────────────────┐  │       │
│   │  │    Obsidian      │  │  │  │  Partner-Desktop │  │       │
│   │  │  • My harnesses  │  │  │  │  • Their harness │  │       │
│   │  │  • My Studio     │  │  │  │  • Their Studio  │  │       │
│   │  └──────────────────┘  │  │  └──────────────────┘  │       │
│   │                        │  │                        │       │
│   │  CA: e421.local        │  │  CA: partner.local     │       │
│   └───────────┬────────────┘  └───────────┬────────────┘       │
│               │                           │                     │
│               │      ┌───────────┐        │                     │
│               └─────►│ Federation│◄───────┘                     │
│                      │  Gateway  │                              │
│                      └───────────┘                              │
│                                                                 │
│   Federation allows:                                            │
│   • e421 can use partner's GPU (if permitted)                   │
│   • Partner can use e421's printer harness (if permitted)       │
│   • Isolated by default, shared by explicit grant               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Trust Model

| Relationship | Default | After Federation |
|--------------|---------|------------------|
| Same domain | Full trust | Full trust |
| Different domain | No trust | Explicit grants only |
| Services | Domain-scoped | Cross-domain with capability |

### Trust Bundle Exchange

```elixir
defmodule Synapsix.Federation.TrustBundle do
  @moduledoc """
  A trust bundle contains the public information needed to
  verify identities from another domain.
  """
  
  defstruct [
    :domain,           # "partner.local"
    :ca_certificates,  # List of CA certs (chain)
    :spiffe_id_prefix, # "spiffe://partner.local/"
    :endpoint,         # How to reach their federation endpoint
    :capabilities      # What they're willing to share
  ]
  
  @doc "Generate a trust bundle to share with another domain"
  def export(my_config) do
    %__MODULE__{
      domain: my_config.domain,
      ca_certificates: [my_config.ca_public_cert],
      spiffe_id_prefix: "spiffe://#{my_config.domain}/",
      endpoint: my_config.federation_endpoint,
      capabilities: my_config.shareable_capabilities
    }
  end
  
  @doc "Import and verify another domain's trust bundle"
  def import(bundle, verification_method) do
    case verification_method do
      :manual -> 
        # User manually verifies fingerprint
        {:ok, bundle}
      {:dns, domain} ->
        # Verify via DNSSEC
        verify_via_dns(bundle, domain)
      {:web, url} ->
        # Verify via well-known endpoint
        verify_via_web(bundle, url)
    end
  end
end
```

### Federation Policies

```elixir
defmodule Synapsix.Federation.Policy do
  @moduledoc """
  Policies governing cross-domain interactions.
  """
  
  # What can a federated user do?
  defmodule AccessPolicy do
    defstruct [
      :allowed_services,      # ["cursor-harness", "printer"]
      :allowed_actions,       # [:read, :execute] (not :admin)
      :rate_limits,           # {requests_per_minute, burst}
      :time_restrictions,     # "weekends only", etc.
      :requires_approval      # :always, :first_time, :never
    ]
  end
  
  def evaluate(request, policy) do
    with :ok <- check_service_allowed(request, policy),
         :ok <- check_action_allowed(request, policy),
         :ok <- check_rate_limit(request, policy),
         :ok <- check_time_restrictions(request, policy) do
      case policy.requires_approval do
        :never -> {:ok, :allowed}
        :first_time -> check_prior_approval(request)
        :always -> {:pending, :awaiting_approval}
      end
    end
  end
end
```

### User Experience

```
┌─────────────────────────────────────────────────────────────────┐
│                    Federation UI Flow                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Partner sends federation request:                           │
│     ┌─────────────────────────────────────────────────────┐    │
│     │  🤝 Federation Request                               │    │
│     │                                                      │    │
│     │  "partner" wants to federate with you.               │    │
│     │                                                      │    │
│     │  They're offering:                                   │    │
│     │  • GPU access (RTX 3090)                             │    │
│     │  • Godot harness                                     │    │
│     │                                                      │    │
│     │  They're requesting:                                 │    │
│     │  • Printer harness access                            │    │
│     │                                                      │    │
│     │  Trust bundle fingerprint:                           │    │
│     │  SHA256: 3f4a8c...verify this matches!               │    │
│     │                                                      │    │
│     │  [Accept]  [Decline]  [Customize]                    │    │
│     └─────────────────────────────────────────────────────┘    │
│                                                                 │
│  2. User customizes access:                                     │
│     ┌─────────────────────────────────────────────────────┐    │
│     │  📝 Access Policy for "partner"                      │    │
│     │                                                      │    │
│     │  Allow access to:                                    │    │
│     │  ☑ Printer harness                                   │    │
│     │  ☐ Cursor harness                                    │    │
│     │  ☐ Local AI models                                   │    │
│     │                                                      │    │
│     │  Rate limit: [100] requests/hour                     │    │
│     │  Require approval: ☐ First time  ☑ Never             │    │
│     │                                                      │    │
│     │  [Save Policy]                                       │    │
│     └─────────────────────────────────────────────────────┘    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 4: Scenario 3 - Team Collaboration

### Description

A small development team wants to share harnesses for pair programming, code review, and collaborative AI sessions.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Team Collaboration Setup                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                  ┌─────────────────────┐                       │
│                  │   Team Trust Root   │                       │
│                  │   (Shared CA/SPIRE) │                       │
│                  └──────────┬──────────┘                       │
│                             │                                   │
│         ┌───────────────────┼───────────────────┐              │
│         │                   │                   │              │
│         ▼                   ▼                   ▼              │
│   ┌───────────┐       ┌───────────┐       ┌───────────┐       │
│   │  Alice    │       │   Bob     │       │  Charlie  │       │
│   │           │       │           │       │           │       │
│   │ • Cursor  │◄─────►│ • Cursor  │◄─────►│ • Cursor  │       │
│   │ • Godot   │       │ • VS Code │       │ • Android │       │
│   │           │       │           │       │           │       │
│   └───────────┘       └───────────┘       └───────────┘       │
│                                                                 │
│   Team Features:                                                │
│   • Shared AI sessions (multiple agents, one codebase)          │
│   • Remote pair programming via harness sharing                 │
│   • Code review with live AI assistance                         │
│   • Shared resource pool (GPUs for inference)                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Trust Model Options

**Option A: Central Team CA**

```
Pros:
• Simple to manage
• Clear authority
• Easy onboarding/offboarding

Cons:
• Single point of failure
• Requires dedicated infrastructure
• Admin burden
```

**Option B: SPIFFE Federation (Recommended)**

```
Pros:
• No central infrastructure needed
• Each member runs their own SPIRE
• Federated trust bundles exchanged
• Decentralized resilience

Cons:
• Higher initial complexity
• More moving parts
```

### Team Roles and Permissions

```elixir
defmodule Synapsix.Team.Roles do
  @roles %{
    admin: %{
      can_manage_federation: true,
      can_invite_members: true,
      can_access_all_harnesses: true,
      can_revoke_access: true
    },
    member: %{
      can_manage_federation: false,
      can_invite_members: false,
      can_access_all_harnesses: true,
      can_revoke_access: false
    },
    guest: %{
      can_manage_federation: false,
      can_invite_members: false,
      can_access_all_harnesses: false,  # Only explicitly shared
      can_revoke_access: false
    }
  }
  
  def permissions_for(role), do: @roles[role]
end
```

### Collaboration Session Example

```elixir
defmodule Synapsix.Team.Session do
  @moduledoc """
  A collaborative session where multiple users interact
  with shared harnesses and AI agents.
  """
  
  defstruct [
    :id,
    :name,
    :participants,      # [%{user: ..., role: :driver | :observer}]
    :shared_harnesses,  # Harnesses accessible in this session
    :shared_agents,     # AI agents shared across participants
    :recording?,        # Whether to record for later review
    :chat_history       # Inter-participant communication
  ]
  
  def start_session(initiator, opts) do
    session = %__MODULE__{
      id: UUID.uuid4(),
      name: opts[:name] || "Pair Session #{Date.utc_today()}",
      participants: [%{user: initiator, role: :driver}],
      shared_harnesses: opts[:harnesses] || [],
      shared_agents: [],
      recording?: opts[:record] || false,
      chat_history: []
    }
    
    # Broadcast session availability to team
    Synapsix.Team.broadcast({:session_started, session})
    
    {:ok, session}
  end
  
  def join_session(session_id, user, role \\ :observer) do
    # Verify user has permission to join
    # Add to participants
    # Sync current state
  end
  
  def transfer_control(session, from_user, to_user) do
    # Driver/observer swap for pair programming
  end
end
```

---

## Part 5: Scenario 4 - Enterprise Integration

### Description

Corporate environments with existing identity infrastructure (Active Directory, Okta, etc.) that want to integrate Synapsix/Continuum.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Enterprise Integration                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                 Corporate Identity                     │    │
│   │              (Okta / Azure AD / LDAP)                  │    │
│   └───────────────────────────────────────────────────────┘    │
│                              │                                  │
│                              │ OIDC / SAML                      │
│                              ▼                                  │
│   ┌───────────────────────────────────────────────────────┐    │
│   │               Continuum Identity Bridge                │    │
│   │                                                        │    │
│   │  • Maps corporate identities to SPIFFE IDs             │    │
│   │  • Enforces corporate policies                         │    │
│   │  • Audit logging for compliance                        │    │
│   │  • SSO integration                                     │    │
│   └───────────────────────────────────────────────────────┘    │
│                              │                                  │
│                              ▼                                  │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                   Synapsix Cluster                     │    │
│   │                                                        │    │
│   │  ┌───────────┐  ┌───────────┐  ┌───────────┐        │    │
│   │  │ Dept A    │  │ Dept B    │  │ Shared    │        │    │
│   │  │ Harnesses │  │ Harnesses │  │ Resources │        │    │
│   │  └───────────┘  └───────────┘  └───────────┘        │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Integration Points

| System | Integration | Purpose |
|--------|-------------|---------|
| **Active Directory** | LDAP bind / OIDC | User authentication |
| **Okta/Auth0** | OIDC | SSO, MFA |
| **HashiCorp Vault** | PKI backend | Certificate issuance |
| **Splunk/ELK** | Syslog/HTTP | Audit logging |
| **ServiceNow** | REST API | Access request workflows |

### Corporate Policy Enforcement

```elixir
defmodule Synapsix.Enterprise.PolicyEngine do
  @moduledoc """
  Enforces corporate policies on harness access.
  """
  
  # Example: Only allow harness access during work hours
  def check_time_policy(user, _action) do
    user_tz = get_user_timezone(user)
    now = DateTime.now!(user_tz)
    
    if now.hour >= 9 and now.hour < 18 and now.day_of_week in 1..5 do
      :ok
    else
      {:error, :outside_work_hours}
    end
  end
  
  # Example: Require manager approval for sensitive harnesses
  def check_approval_policy(user, harness) do
    if harness.classification == :sensitive do
      case get_approval_status(user, harness) do
        :approved -> :ok
        :pending -> {:error, :awaiting_approval}
        :none -> {:error, :approval_required}
      end
    else
      :ok
    end
  end
  
  # Example: Data loss prevention
  def check_dlp_policy(action, content) do
    if action in [:type_text, :paste] do
      case DLP.scan(content) do
        :clean -> :ok
        {:violation, type} -> {:error, {:dlp_violation, type}}
      end
    else
      :ok
    end
  end
end
```

### Compliance Considerations

| Requirement | Implementation |
|-------------|----------------|
| **Audit trail** | All harness actions logged with user identity |
| **Access reviews** | Periodic reports of who accessed what |
| **Data residency** | Harnesses tagged with geographic restrictions |
| **Encryption at rest** | All stored data encrypted |
| **Key management** | HSM for production key material |

---

## Part 6: Scenario 5 - Public Service Provider

### Description

A service provider offers Synapsix harnesses to customers as a managed service ("Harness-as-a-Service").

```
┌─────────────────────────────────────────────────────────────────┐
│                  Harness-as-a-Service Provider                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Provider Infrastructure                                       │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                                                        │    │
│   │   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐│    │
│   │   │ GPU Cluster │   │ CPU Workers │   │ Storage     ││    │
│   │   │ (A100s)     │   │ (inference) │   │ (sessions)  ││    │
│   │   └─────────────┘   └─────────────┘   └─────────────┘│    │
│   │                                                        │    │
│   │   ┌─────────────────────────────────────────────────┐│    │
│   │   │              Multi-tenant Platform              ││    │
│   │   │  • Tenant isolation (k8s namespaces)            ││    │
│   │   │  • Resource quotas                              ││    │
│   │   │  • Billing integration                          ││    │
│   │   │  • SLA monitoring                               ││    │
│   │   └─────────────────────────────────────────────────┘│    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                              │                                  │
│            ┌─────────────────┼─────────────────┐               │
│            │                 │                 │               │
│            ▼                 ▼                 ▼               │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐        │
│   │  Customer A │   │  Customer B │   │  Customer C │        │
│   │  (startup)  │   │  (agency)   │   │  (solo dev) │        │
│   │             │   │             │   │             │        │
│   │ spiffe://   │   │ spiffe://   │   │ spiffe://   │        │
│   │ custA.haas/ │   │ custB.haas/ │   │ custC.haas/ │        │
│   └─────────────┘   └─────────────┘   └─────────────┘        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Multi-tenant Trust Model

| Component | Isolation Level |
|-----------|-----------------|
| **Network** | Separate k8s namespaces, network policies |
| **Identity** | Per-tenant SPIFFE trust domain |
| **Secrets** | Separate Vault paths per tenant |
| **Data** | Encrypted at rest with tenant-specific keys |
| **Compute** | Resource quotas, optional dedicated nodes |

### Customer Federation (Hybrid Model)

```elixir
defmodule Synapsix.HaaS.TenantFederation do
  @moduledoc """
  Allows HaaS customers to federate with the provider
  while maintaining their own trust domain.
  """
  
  defstruct [
    :tenant_id,
    :tenant_trust_domain,    # Customer's own trust domain
    :provider_trust_domain,  # Provider's domain for this tenant
    :federation_type         # :provider_controlled | :customer_controlled | :hybrid
  ]
  
  def setup_federation(tenant, opts) do
    case opts[:federation_type] do
      :provider_controlled ->
        # Provider issues all certificates
        # Simplest for customers, least control
        setup_provider_controlled(tenant)
        
      :customer_controlled ->
        # Customer runs their own SPIRE, federates with provider
        # Most control, most complex
        setup_customer_controlled(tenant)
        
      :hybrid ->
        # Provider manages infrastructure, customer has own identity
        # Balance of control and simplicity
        setup_hybrid(tenant)
    end
  end
end
```

### Licensing and Billing Integration

```elixir
defmodule Synapsix.HaaS.Billing do
  @moduledoc """
  Track usage for billing purposes.
  """
  
  defstruct [
    :tenant_id,
    :period,
    :harness_minutes,    # Total harness execution time
    :gpu_minutes,        # GPU time consumed
    :storage_gb_hours,   # Storage usage
    :api_calls,          # API requests
    :federated_requests  # Cross-tenant requests (may be premium)
  ]
  
  def record_usage(tenant, event) do
    case event do
      {:harness_started, harness_id, _} ->
        start_metering(tenant, :harness, harness_id)
        
      {:harness_stopped, harness_id, duration} ->
        record_metered(tenant, :harness_minutes, duration)
        
      {:gpu_allocated, amount} ->
        start_metering(tenant, :gpu, amount)
        
      {:api_call, _endpoint} ->
        increment(tenant, :api_calls)
    end
  end
end
```

---

## Part 7: Trust Bundle Format

### Standard Format

```json
{
  "version": "1.0",
  "trust_domain": "e421.homelab",
  "issued_at": "2026-01-29T12:00:00Z",
  "expires_at": "2027-01-29T12:00:00Z",
  
  "x509_authorities": [
    {
      "type": "x509",
      "data": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
    }
  ],
  
  "jwt_authorities": [
    {
      "type": "jwt",
      "key_id": "key-1",
      "public_key": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
    }
  ],
  
  "federation_endpoint": "https://federation.e421.homelab:8443",
  
  "capabilities": {
    "shareable_services": ["cursor-harness", "printer-harness"],
    "accepts_delegation": true,
    "max_delegation_depth": 2
  },
  
  "signature": "..."
}
```

### Verification Methods

| Method | Security | Convenience |
|--------|----------|-------------|
| **Manual fingerprint** | Highest | Lowest |
| **DNSSEC** | High | Medium |
| **Well-known endpoint** | Medium | High |
| **Web of trust** | Variable | Medium |

---

## Part 8: Implementation Recommendations

### Phase 1: Single User (Now)

| Task | Priority |
|------|----------|
| Implement mTLS for BEAM distribution | HIGH |
| Create basic trust configuration | HIGH |
| Certificate generation scripts | MEDIUM |

### Phase 2: Shared Homelab (Q2 2026)

| Task | Priority |
|------|----------|
| Trust bundle import/export | HIGH |
| Basic federation policies | HIGH |
| Cross-domain capability tokens | MEDIUM |

### Phase 3: Team Collaboration (Q3 2026)

| Task | Priority |
|------|----------|
| SPIFFE integration | HIGH |
| Team roles and permissions | HIGH |
| Collaborative sessions | MEDIUM |

### Phase 4: Enterprise (Future)

| Task | Priority |
|------|----------|
| OIDC/SAML integration | HIGH |
| Audit logging | HIGH |
| Compliance reporting | MEDIUM |

---

## Part 9: Security Considerations

### Attack Vectors by Scenario

| Scenario | Key Threats | Mitigations |
|----------|-------------|-------------|
| **Single User** | Device theft, key compromise | Short-lived certs, device revocation |
| **Shared Homelab** | Privilege escalation, eavesdropping | mTLS, capability tokens, audit logs |
| **Team** | Insider threat, compromised member | Least privilege, session recording |
| **Enterprise** | APT, data exfiltration | DLP, SIEM integration, HSM |
| **Public Service** | Tenant isolation breach, DDoS | Network policies, rate limiting |

### Defense in Depth

```
┌─────────────────────────────────────────────────────────────────┐
│                    Defense in Depth Layers                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Layer 5: Application                                          │
│   ├─ Capability-based authorization (Macaroons)                 │
│   ├─ Input validation                                           │
│   └─ Session management                                         │
│                                                                 │
│   Layer 4: Service Identity                                     │
│   ├─ SPIFFE/SPIRE                                               │
│   ├─ JWT tokens                                                 │
│   └─ Service mesh policies                                      │
│                                                                 │
│   Layer 3: Transport                                            │
│   ├─ mTLS everywhere                                            │
│   ├─ Certificate rotation                                       │
│   └─ Protocol encryption                                        │
│                                                                 │
│   Layer 2: Network                                              │
│   ├─ WireGuard mesh                                             │
│   ├─ Network policies                                           │
│   └─ Firewall rules                                             │
│                                                                 │
│   Layer 1: Physical/Infrastructure                              │
│   ├─ Encrypted storage                                          │
│   ├─ Secure boot                                                │
│   └─ Physical security                                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Summary

| Scenario | Complexity | Trust Model | Recommended For |
|----------|------------|-------------|-----------------|
| **Single User, Multi-Machine** | Low | Single CA | Personal use (Phase 1) |
| **Shared Homelab** | Medium | Federated CAs | Family/roommates |
| **Team Collaboration** | Medium | Team CA or SPIFFE | Small teams |
| **Enterprise** | High | OIDC + SPIFFE | Corporate |
| **Public Service** | High | Multi-tenant SPIFFE | Service providers |

**Key Takeaways**:

1. Start simple (single CA), evolve toward federation
2. SPIFFE is the foundation for cross-domain trust
3. Capability tokens (Macaroons) enable fine-grained delegation
4. Defense in depth at every layer
5. Plan for the scenarios you need now, architect for future growth

---

*Document created as architectural analysis for Continuum Studio federation requirements.*

