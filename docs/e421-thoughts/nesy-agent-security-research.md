# Neurosymbolic AI for Agent Security Research

**Created**: January 31, 2026
**Purpose**: Researching open source, self-hostable neurosymbolic AI solutions for creating verifiable, secure AI agents.

## Goal

Build a local-hostable system that:
- Provides security for regular users ("the everyman")
- Forces companies to open source their tech if they want to use the system
- Enables verifiable reasoning chains
- Prevents unauthorized agent actions through formal verification

## Key Findings

### 1. Imandra Universe (Commercial Reference)

**URL**: https://www.imandra.ai/articles/imandra-universe-launch

- Platform for Neurosymbolic AI Agents with Logical Reasoning
- "AI Assistants like ChatGPT, Claude and Cursor Can Now Tap Directly into Logical Reasoning via Imandra Universe's Reasoning as a Service® MCP Servers"
- MCP server integration (directly compatible with Cursor)
- Cloud-scale automated formal verification

**Note**: This is a commercial SaaS - not suitable for our open-source, self-hosted goal, but demonstrates the architecture pattern we want.

### 2. neuro-san (Open Source)

**URL**: https://github.com/cognizant-ai-lab/neuro-san
**License**: Apache 2.0 (permissive, usable)
**Status**: Active (v0.6.27 released 2 days ago)

"Neuro AI system of agent networks (Neuro SAN) is a library for building data-driven multi-agent networks which can be run as a library, or served up via an HTTP server."

**Features**:
- Python-based (97.3%)
- Multi-agent systems framework
- Can run as library OR HTTP server (self-hostable!)
- LangChain integration
- 134 releases, active development
- MCP integration in progress

**Relevance**: This could be a base framework for building our secure agent orchestration layer.

### 3. OpenSSA (Open Source Specialist Agents)

**URL**: https://aitomatic.github.io/openssa

- Domain-Aware Neurosymbolic Agents (DANA) architecture
- Hierarchical Task Planning (HTP)
- OODAR reasoning (Observe-Orient-Decide-Act Reasoning)
- Designed for industrial problem-solving

**Relevance**: The DANA architecture provides formal reasoning constraints that could prevent unauthorized actions.

### 4. Key Technology Components

From research overview:

| Component | Purpose |
|-----------|---------|
| **Ollama** | Run open-source models locally (Llama 3, Mistral) |
| **Open Interpreter** | Local code execution with validation |
| **Symbolic Logic Integration** | Combine neural flexibility with formal logic rules |
| **Hierarchical Task Planning** | Structured, verifiable action sequences |

### 5. Academic Papers of Interest

- "Towards Formal Verification of Neuro-symbolic Multi-agent Systems" (IJCAI 2023) by Kouvaros
- "Neuro-Symbolic AI for Cybersecurity: State of the Art" (arXiv)
- "Neurosymbolic Reinforcement Learning with Formally..." (NeurIPS 2025)
- "A Survey on Verification and Validation, Testing and Evaluations of Neurosymbolic AI"

## Architecture Concept

Based on research, a viable architecture for self-hosted secure agents:

```
┌─────────────────────────────────────────────────────────┐
│                     Synapsix / Continuum Studio          │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────────┐    ┌─────────────────────────────┐ │
│  │  Neural Layer   │    │   Symbolic Logic Layer      │ │
│  │  (LLM/Agent)    │◄──►│   (Formal Verification)     │ │
│  │  - Ollama       │    │   - neuro-san rules         │ │
│  │  - Local models │    │   - Action constraints      │ │
│  └─────────────────┘    │   - Permission checking     │ │
│                          └─────────────────────────────┘ │
│                                     │                    │
│                          ┌──────────▼──────────┐        │
│                          │  Action Verification │        │
│                          │  - Must pass rules   │        │
│                          │  - Audit trail       │        │
│                          │  - Rollback support  │        │
│                          └─────────────────────┘        │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## Open Source Strategy

To force companies to open source if they want to use our system:

1. **AGPL-3.0 License** (or similar strong copyleft):
   - Requires source disclosure for network services
   - Companies using our agent security system must open their modifications

2. **Verification Module as Core Dependency**:
   - All agent actions must pass through our verification
   - Can't strip it out without breaking functionality

3. **Cryptographic Audit Trail**:
   - Actions signed and logged
   - Tampering detectable
   - Public verifiability

## Next Steps

1. [ ] Deep dive into neuro-san architecture
2. [ ] Evaluate DANA (Domain-Aware Neurosymbolic Agents) from OpenSSA
3. [ ] Design formal verification rules for Cursor agent actions
4. [ ] Prototype integration with cursor-proxy interception
5. [ ] Research AGPL licensing implications

## Building From Scratch: Why and How

### Why Build Custom

**MCP Flaws**:
- JSON-RPC overhead and latency
- No strong typing at protocol level
- Trust model is implicit, not verifiable
- No formal specification for behavior

**Existing Framework Shortcomings**:
- neuro-san: Python (slow, GIL limitations)
- OpenSSA: Heavy dependencies, enterprise-oriented
- Most frameworks: No formal verification integration

### Custom Stack Design

Using our preferred languages (Elixir, Rust, Zig, Nix):

```
┌────────────────────────────────────────────────────────────────────┐
│                    SYNAPSIX NESY STACK                             │
├────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │                 Elixir/OTP Orchestration Layer               │  │
│  │  • Agent lifecycle management (GenServer, Supervisors)       │  │
│  │  • Message passing and routing                               │  │
│  │  • Fault tolerance and hot reloading                         │  │
│  │  • Distributed clustering (libcluster)                       │  │
│  └──────────────────────────┬──────────────────────────────────┘  │
│                              │                                      │
│          ┌───────────────────┼───────────────────┐                 │
│          │                   │                   │                 │
│          ▼                   ▼                   ▼                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐  │
│  │ Neural NIFs  │   │ Symbolic NIFs │   │ Verification NIFs    │  │
│  │ (Rust/Zig)   │   │ (Rust/Zig)    │   │ (Rust)               │  │
│  │              │   │               │   │                      │  │
│  │ • LLM calls  │   │ • SMT solver  │   │ • Proof generation   │  │
│  │ • Embedding  │   │ • Logic prog  │   │ • Constraint check   │  │
│  │ • Inference  │   │ • Rule engine │   │ • Audit trail        │  │
│  └──────────────┘   └───────────────┘   └──────────────────────┘  │
│                                                                     │
├────────────────────────────────────────────────────────────────────┤
│  Communication Protocol (NOT MCP)                                   │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  Option A: Cap'n Proto (zero-copy, schema-based, fast)        │ │
│  │  Option B: Flatbuffers (memory-efficient, typed)              │ │
│  │  Option C: Custom binary with formal spec (most control)      │ │
│  └───────────────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────────────────┤
│  Formal Specification Layer                                         │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │  • TLA+ or Alloy for protocol modeling                        │ │
│  │  • Z3 or CVC5 for runtime constraint solving                  │ │
│  │  • Lean or Coq for theorem proving (optional, advanced)       │ │
│  └───────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### Component Deep Dive

#### 1. Elixir Orchestration (The "Spine")

Why Elixir:
- BEAM VM: battle-tested for fault tolerance
- Supervision trees: agents crash? restart them!
- Hot code reloading: update rules without downtime
- Distributed by default: scale across machines

```elixir
# Example: Agent with verification constraint
defmodule Synapsix.NeSy.VerifiedAgent do
  use GenServer
  
  def execute_action(agent, action) do
    with {:ok, proof} <- SymbolicNif.verify_action(action),
         {:ok, result} <- NeuralNif.execute(action) do
      AuditLog.record(agent, action, proof)
      {:ok, result}
    else
      {:error, :constraint_violation, reason} ->
        {:blocked, reason}
    end
  end
end
```

#### 2. Rust NIFs (The "Muscle")

Native Implemented Functions for:
- **Z3 bindings**: Runtime SMT solving
- **LLM inference**: GGML/llama.cpp integration
- **Proof checking**: Verify formal proofs

```rust
// Example: Constraint verification NIF
#[rustler::nif]
fn verify_action(action: ActionSpec) -> Result<Proof, VerificationError> {
    let solver = Z3Solver::new();
    solver.add_constraint(action.preconditions());
    solver.add_constraint(action.safety_rules());
    
    match solver.check() {
        SatResult::Sat => Ok(solver.get_proof()),
        SatResult::Unsat => Err(VerificationError::ConstraintViolation),
    }
}
```

#### 3. Zig Components (The "Precision")

For ultra-low-level, embedded constraints:
- Custom memory allocators for proof objects
- Hardware security module (HSM) integration
- Cryptographic signature generation

#### 4. Communication Protocol

**NOT MCP** - Design principles:
- Formally specified (TLA+)
- Zero-copy serialization (Cap'n Proto)
- Built-in authentication
- Proof-carrying messages

```
Message := {
  sender: AgentID,
  action: Action,
  proof: Option<Proof>,
  signature: Signature,
  timestamp: u64,
}
```

### Phase Implementation Plan

1. **Phase 1: Core Elixir Scaffolding**
   - Agent GenServer with constraint hooks
   - Basic rule engine in pure Elixir
   - Audit logging system

2. **Phase 2: Rust NIFs**
   - Z3 solver bindings
   - Basic proof generation
   - LLM integration (Ollama client)

3. **Phase 3: Formal Specification**
   - TLA+ model of protocol
   - Property-based testing (StreamData)
   - Constraint language DSL

4. **Phase 4: Distribution**
   - libcluster for node discovery
   - Distributed proof verification
   - Cross-node audit trail

5. **Phase 5: Hardware Security**
   - TPM integration (optional)
   - Secure enclave support
   - Hardware-backed signatures

### Advantages Over Existing Approaches

| Aspect | Existing (neuro-san, etc.) | Our Stack |
|--------|----------------------------|-----------|
| **Language** | Python (GIL, slow) | Elixir + Rust (concurrent, fast) |
| **Fault Tolerance** | None built-in | BEAM supervision trees |
| **Formal Verification** | Partial/none | First-class citizen |
| **Distribution** | Needs external tools | Native (Erlang distribution) |
| **Hot Reloading** | Restart required | Hot code swap |
| **Protocol** | MCP/REST | Custom, formally specified |

## Resources

- neuro-san: https://github.com/cognizant-ai-lab/neuro-san
- OpenSSA: https://aitomatic.github.io/openssa
- Kouvaros formal verification paper: https://pkouvaros.github.io/IJCAI23-K/paper
- Neuro-Symbolic AI for Cybersecurity: https://arxiv.org/abs/... (need to find full URL)
- Z3 Rust bindings: https://github.com/prove-rs/z3.rs
- Cap'n Proto: https://capnproto.org/
- TLA+: https://lamport.azurewebsites.net/tla/tla.html
