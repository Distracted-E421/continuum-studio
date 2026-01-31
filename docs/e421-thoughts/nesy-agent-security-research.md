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

## Z3 SMT Solver Deep Dive

**Research Date**: January 31, 2026

### What is Z3?

Z3 is a Satisfiability Modulo Theories (SMT) solver from Microsoft Research. It's a symbolic logic solver foundational to many software engineering tools:

- **Program verification**
- **Compiler validation**
- **Testing and fuzzing**
- **Network verification**
- **Optimization**

**Status**: Open source, actively maintained, 11.7k GitHub stars

### Z3 Rust Bindings (z3.rs)

**URL**: https://github.com/prove-rs/z3.rs  
**Crate**: https://docs.rs/z3/latest/z3/  
**Version**: 0.19.7

The z3.rs project provides two crates:
- **`z3`** - High-level, idiomatic Rust bindings
- **`z3-sys`** - Low-level C API bindings

#### Basic Usage Pattern

```rust
use z3::*;

// Create context and solver
let cfg = Config::new();
let ctx = Context::new(&cfg);
let solver = Solver::new(&ctx);

// Define symbolic variables
let action_allowed = Bool::new_const(&ctx, "action_allowed");
let has_permission = Bool::new_const(&ctx, "has_permission");
let is_safe = Bool::new_const(&ctx, "is_safe");

// Define constraints
// action_allowed ↔ (has_permission ∧ is_safe)
solver.assert(&action_allowed._eq(&Bool::and(&ctx, &[&has_permission, &is_safe])));

// Assert what we know
solver.assert(&has_permission);  // User has permission

// Check if action can be safe
match solver.check() {
    SatResult::Sat => {
        let model = solver.get_model().unwrap();
        println!("Solution exists: is_safe = {}", model.eval(&is_safe, true));
    }
    SatResult::Unsat => println!("No valid assignment exists"),
    SatResult::Unknown => println!("Solver timed out"),
}
```

#### Key Components for Our Use Case

| Component | Purpose | Synapsix Use |
|-----------|---------|--------------|
| **Solver** | Constraint checking | Verify agent actions against rules |
| **Bool** | Boolean logic | Permission predicates |
| **Int** | Integer constraints | Resource limits, counters |
| **Array** | Collections | Action history, context |
| **Datatype** | Custom types | Action specs, proof objects |
| **Tactics** | Solving strategies | Optimize for speed vs completeness |

#### Proof Capabilities (Z3 4.12.0+)

Z3 can generate **proof logs** and **inference traces**:
- Callbacks for every inferred clause
- Proof hints that justify inferences
- Lightweight proof checkers can validate steps
- Enables **verifiable reasoning chains** - exactly what we need!

### Z3-Elixir Integration Options

#### Option 1: ex_smt (Existing Binding)

**URL**: https://github.com/tsutsu/ex_smt  
**Status**: Minimal (5 stars), possibly unmaintained

Not recommended as primary solution due to low activity.

#### Option 2: Rustler NIF (Recommended)

Build our own Z3 NIF using:
- **Rustler**: Ergonomic Rust NIF development
- **z3.rs**: Idiomatic Rust Z3 bindings
- **rustler_precompiled**: Distribute without compilation overhead

```elixir
# Synapsix.NeSy.Z3Nif (proposed)
defmodule Synapsix.NeSy.Z3Nif do
  use Rustler, otp_app: :synapsix, crate: "synapsix_z3"
  
  # NIFs (implemented in Rust)
  def new_solver(), do: :erlang.nif_error(:nif_not_loaded)
  def add_constraint(_solver, _constraint), do: :erlang.nif_error(:nif_not_loaded)
  def check_sat(_solver), do: :erlang.nif_error(:nif_not_loaded)
  def get_model(_solver), do: :erlang.nif_error(:nif_not_loaded)
  def get_proof(_solver), do: :erlang.nif_error(:nif_not_loaded)
end
```

#### NIF Safety Considerations

⚠️ **Important**: NIFs can crash the BEAM if not handled carefully.

- Keep NIF execution time short (< 1ms ideal, < 100ms acceptable)
- Use `enif_consume_timeslice` for longer operations
- Consider **Dirty NIFs** for CPU-bound work
- Fallback to **Ports** for potentially unstable integrations

### AutoRocq: Agentic Program Verification

**Paper**: arXiv:2511.17330 (November 2025)  
**Authors**: Tu, Zhao, Song, Zafar, Meng, Roychoudhury

#### Key Innovation

AutoRocq demonstrates **LLM agent + theorem prover collaboration**:

1. LLM agent generates proof attempts
2. Rocq (Coq) theorem prover provides feedback
3. Iterative refinement loop improves proof
4. Final proof is **machine-verified**

#### Relevance to Synapsix

This is the **exact pattern** we want:
- Agent proposes action → Z3 verifies constraints
- If verification fails → Agent receives feedback
- Iterative refinement until verified
- Final action is **provably safe**

```
┌─────────────────────────────────────────────────────────┐
│                    AutoRocq Pattern                      │
├─────────────────────────────────────────────────────────┤
│                                                          │
│   ┌─────────────┐      feedback      ┌───────────────┐  │
│   │  LLM Agent  │◄──────────────────│ Theorem Prover │  │
│   │             │──────────────────►│ (Rocq/Z3)      │  │
│   └─────────────┘   proof attempt   └───────────────┘  │
│          │                                  │           │
│          │ iterate until verified           │           │
│          └──────────────────────────────────┘           │
│                         │                               │
│                         ▼                               │
│              ┌─────────────────────┐                   │
│              │ Machine-Verified    │                   │
│              │ Proof Certificate   │                   │
│              └─────────────────────┘                   │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### Synapsix Adaptation

```elixir
defmodule Synapsix.NeSy.VerificationLoop do
  @max_iterations 5
  
  def verify_action(agent, action, rules) do
    do_verify(agent, action, rules, 0)
  end
  
  defp do_verify(_agent, _action, _rules, @max_iterations) do
    {:error, :max_iterations_exceeded}
  end
  
  defp do_verify(agent, action, rules, iteration) do
    case Z3Nif.check_action(action, rules) do
      {:ok, proof} -> 
        {:verified, action, proof}
      
      {:unsat, feedback} ->
        # Ask agent to refine action based on feedback
        refined_action = Agent.refine_action(agent, action, feedback)
        do_verify(agent, refined_action, rules, iteration + 1)
      
      {:unknown, reason} ->
        {:inconclusive, reason}
    end
  end
end
```

### Z3 Proof Generation Deep Dive

#### Proof Logging API (Z3 4.12.0+)

Z3 exposes `Z3_solver_register_on_clause` callback for capturing inferences:

| Inference Type | Description |
|----------------|-------------|
| **assumption** | Clause entailed by input formula |
| **deletion** | Removing clause from active set |
| **rup** | Reverse Unit Propagation (propositional reasoning) |
| **smt/theory** | Theory tautologies (EUF, LIA, etc.) |

**Proof Hints**: Z3 generates "big step" inferences:
- **tseitin**: Tseitin transformation (CNF encoding)
- **euf**: Equality reasoning (uninterpreted functions)
- **inst**: Quantifier instantiation with bindings
- **farkas**: Linear arithmetic via Farkas lemma
- **bound**: Inequality derivation via cuts

#### Proof Logging Example

```
; Enable proof logging
(set-option :sat.euf true)
(set-option :tactic.default_tactic smt)
(set-option :solver.proof.log proof_log.smt2)

; Your constraints here...
(check-sat)
```

The log contains SMTLIB-extended commands:
- `(infer clause proof_hint)` - Derived clause with justification
- `(del clause)` - Clause deletion
- `(assume clause)` - Assumption introduction

#### Self-Validation

Z3 includes built-in proof validators:

```bash
z3 problem.smt2 sat.euf=true tactic.default_tactic=smt solver.proof.check=true
```

Output shows validation statistics:
```
(proofs +tseitin 60 +alldiff 8 +euf 3 +rup 5 +inst 6 -quant 3 -inst 2)
```
- `+X N`: N inferences validated by syntactic check
- `-X N`: N inferences required SMT solving to validate

### Alethe Proof Format

**URL**: https://verit.loria.fr/documentation/alethe-spec.pdf

Alethe is a generic SMT proof format designed for unsatisfiability proofs:

| Property | Description |
|----------|-------------|
| **Style** | Natural deduction + resolution |
| **Base** | SMT-LIB syntax |
| **Solvers** | veriT, cvc5 |
| **Checkers** | Carcara (Rust), Isabelle/HOL, Coq (SMTCoq) |

#### Key Features

1. **Contexts**: λ-term based substitution mechanism for preprocessing proofs
2. **90+ Proof Rules**: Comprehensive coverage of SMT reasoning
3. **Subproofs**: Nested proofs for lemmas and skolemization
4. **Formal Semantics**: Soundness proof provided in spec

#### Carcara: High-Performance Rust Proof Checker

**URL**: https://github.com/ufmg-smite/carcara  
**License**: Apache-2.0 (permissive, compatible with AGPL/SSPL)  
**Paper**: TACAS 2023  
**Stars**: 40+ (active development by UFMG SMITE research group)

#### Why Carcara is Perfect for Synapsix

1. **Written in Rust** → Can be compiled to NIF for Elixir
2. **High performance** → Efficient verification of large proofs
3. **Library API** → Not just CLI, has programmatic interface
4. **Parallel checking** → Can utilize multiple cores
5. **Elaboration** → Can fill in proof details for coarse-grained steps
6. **Apache-2.0** → Compatible with our licensing strategy

#### Carcara API (from lib.rs)

```rust
// Main proof checking function
pub fn check<T: AsRef<str>>(
    problem: T,           // SMT-LIB problem file
    proof: T,             // Alethe proof file
    rules: Option<T>,     // Optional custom rules
    parser_config: parser::Config,
    checker_config: checker::Config,
    collect_stats: bool,
) -> Result<bool, Error>

// Parallel proof checking
pub fn check_parallel<T: AsRef<str>>(
    problem: T,
    proof: T,
    rules: Option<T>,
    parser_config: parser::Config,
    checker_config: checker::Config,
    collect_stats: bool,
    num_threads: usize,   // Parallel workers
    stack_size: usize,
) -> Result<bool, Error>

// Check and elaborate (fill in proof details)
pub fn check_and_elaborate<T: AsRef<str>>(
    problem: T,
    proof: T,
    rules: Option<T>,
    parser_config: parser::Config,
    checker_config: checker::Config,
    elaborator_config: elaborator::Config,
    pipeline: Vec<elaborator::ElaborationStep>,
    collect_stats: bool,
) -> Result<(bool, ast::Problem, ast::Proof, ast::PrimitivePool), Error>
```

#### Public Modules

| Module | Purpose |
|--------|---------|
| `ast` | Abstract syntax tree for proofs |
| `checker` | Core proof checking logic |
| `parser` | Alethe proof parsing |
| `elaborator` | Proof elaboration/refinement |
| `benchmarking` | Performance measurement |

#### NIF Integration Plan

```rust
// synapsix_carcara/native/synapsix_carcara/src/lib.rs

use rustler::{Encoder, Env, NifResult, Term};

#[rustler::nif]
fn verify_proof(
    problem_smt: String,
    proof_alethe: String,
) -> NifResult<(bool, String)> {
    let parser_config = carcara::parser::Config::default();
    let checker_config = carcara::checker::Config::default();
    
    match carcara::check(
        &problem_smt,
        &proof_alethe,
        None::<&str>,
        parser_config,
        checker_config,
        true, // collect stats
    ) {
        Ok(valid) => Ok((valid, "Proof verified".to_string())),
        Err(e) => Ok((false, format!("Verification failed: {}", e))),
    }
}

#[rustler::nif(schedule = "DirtyCpu")]
fn verify_proof_parallel(
    problem_smt: String,
    proof_alethe: String,
    num_threads: usize,
) -> NifResult<(bool, String)> {
    // Use dirty scheduler for CPU-intensive work
    let parser_config = carcara::parser::Config::default();
    let checker_config = carcara::checker::Config::default();
    
    match carcara::check_parallel(
        &problem_smt,
        &proof_alethe,
        None::<&str>,
        parser_config,
        checker_config,
        true,
        num_threads,
        4 * 1024 * 1024, // 4MB stack per thread
    ) {
        Ok(valid) => Ok((valid, "Proof verified".to_string())),
        Err(e) => Ok((false, format!("Verification failed: {}", e))),
    }
}

rustler::init!("Elixir.Synapsix.NeSy.CarcaraNif", [
    verify_proof,
    verify_proof_parallel,
]);
```

```elixir
# lib/synapsix/nesy/carcara_nif.ex
defmodule Synapsix.NeSy.CarcaraNif do
  use Rustler, otp_app: :synapsix, crate: "synapsix_carcara"
  
  @doc "Verify an Alethe proof against an SMT-LIB problem"
  def verify_proof(_problem_smt, _proof_alethe), 
    do: :erlang.nif_error(:nif_not_loaded)
  
  @doc "Verify proof using multiple threads (dirty scheduler)"
  def verify_proof_parallel(_problem_smt, _proof_alethe, _num_threads),
    do: :erlang.nif_error(:nif_not_loaded)
end
```

#### Cargo.toml for NIF

```toml
[package]
name = "synapsix_carcara"
version = "0.1.0"
edition = "2021"

[lib]
name = "synapsix_carcara"
path = "src/lib.rs"
crate-type = ["cdylib"]

[dependencies]
rustler = "0.31"
carcara = { git = "https://github.com/ufmg-smite/carcara.git" }

[profile.release]
opt-level = 3
lto = true
```

This is exactly what we need for Synapsix:
- Written in Rust (can be compiled to NIF)
- High performance
- Validates Alethe proofs
- Can be called from Elixir with minimal overhead

### Synapsix Proof Integration Strategy

```
┌──────────────────────────────────────────────────────────────────┐
│              SYNAPSIX PROOF ARCHITECTURE                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────┐                                         │
│  │ Elixir Orchestrator │                                         │
│  │  • GenServer        │                                         │
│  │  • Constraint DSL   │                                         │
│  └──────────┬──────────┘                                         │
│             │                                                     │
│             ▼                                                     │
│  ┌─────────────────────┐    ┌─────────────────────┐             │
│  │   Z3 Solver NIF     │───►│   Proof Logger      │             │
│  │   (z3.rs + Rustler) │    │   (Alethe format)   │             │
│  └──────────┬──────────┘    └──────────┬──────────┘             │
│             │                          │                         │
│             ▼                          ▼                         │
│  ┌─────────────────────┐    ┌─────────────────────┐             │
│  │   SAT/UNSAT Result  │    │   Carcara Checker   │             │
│  │   + Model (if SAT)  │    │   (Rust NIF)        │             │
│  └─────────────────────┘    └──────────┬──────────┘             │
│                                        │                         │
│                                        ▼                         │
│                             ┌─────────────────────┐             │
│                             │ Verified Proof      │             │
│                             │ Certificate         │             │
│                             └─────────────────────┘             │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

### Proof-Carrying Agent Actions

With this architecture, every agent action can carry a proof:

```elixir
defmodule Synapsix.NeSy.ProofCarrying do
  @moduledoc """
  Agent actions with machine-verifiable proofs.
  """
  
  defstruct [
    :action,           # The action to perform
    :constraints,      # SMT constraints that must hold
    :proof_log,        # Alethe proof log (binary)
    :verified?,        # Has Carcara verified this?
    :timestamp,
    :signature         # Cryptographic signature
  ]
  
  def execute(%__MODULE__{verified?: true} = pca) do
    # Only execute if proof is verified
    AuditLog.record(pca)
    Action.execute(pca.action)
  end
  
  def execute(%__MODULE__{verified?: false} = pca) do
    # Attempt verification first
    case CacaraNif.verify(pca.proof_log) do
      {:ok, _stats} -> execute(%{pca | verified?: true})
      {:error, reason} -> {:blocked, :proof_invalid, reason}
    end
  end
end
```

### Implementation Priority

For Synapsix NeSy stack, Z3 integration should be:

1. **Phase 1**: Pure Elixir constraint DSL (prototype)
2. **Phase 2**: Rustler NIF with z3.rs (production)
3. **Phase 3**: Proof logging and verification (Alethe format)
4. **Phase 4**: Carcara NIF for proof checking
5. **Phase 5**: AutoRocq-style iterative refinement loop

## Cap'n Proto: NOT-MCP Protocol Design

**Research Date**: January 31, 2026

### Why Cap'n Proto Over MCP?

| Aspect | MCP (JSON-RPC) | Cap'n Proto |
|--------|----------------|-------------|
| **Serialization** | JSON encode/decode | Zero-copy |
| **Typing** | Runtime schema | Compile-time schema |
| **Latency** | Multiple round trips | Promise pipelining |
| **Security** | Implicit trust | Capability-based |
| **Protocol** | Text-based | Binary, platform-independent |
| **Object model** | Global endpoints | Distributed objects |

### Core Cap'n Proto Features

#### 1. Zero-Copy Serialization

No encode/decode step - data is used directly from wire format:
- Platform-independent binary encoding
- Fixed widths, offsets, proper alignment
- Pointers are offset-based (position-independent)
- Little-endian for CPU efficiency

#### 2. Promise Pipelining ("Time Travel")

```
# Traditional RPC: 4 round trips
bar = root.open("bar")     # RT 1
foo = bar.open("foo")      # RT 2
size = foo.size()          # RT 3
data = foo.read(0, size)   # RT 4

# Cap'n Proto: 1 round trip!
# All calls sent together, results pipelined
```

This is critical for our NeSy stack where verification may require multiple checks.

#### 3. Capability-Based Security

**Interface references are capabilities** - they both:
1. Designate an object to call
2. Confer permission to call it

This is EXACTLY what we need for agent action verification:
- An action reference grants permission to execute
- No separate authentication/authorization needed
- Permission flows with the reference

```
# Only the holder of the reference can call it
action = agent.propose_action(constraints)
# If verification passes, we can pass the reference to execute
executor.execute(action)  # Execute only grants permission
```

### Cap'n Proto Schema for Synapsix

```capnp
@0xabcd1234efgh5678;

# Synapsix NeSy Protocol

struct AgentAction {
  id @0 :UInt64;
  type @1 :ActionType;
  target @2 :Text;
  parameters @3 :AnyPointer;
  
  enum ActionType {
    fileRead @0;
    fileWrite @1;
    shellExecute @2;
    networkRequest @3;
    userPrompt @4;
  }
}

struct Constraint {
  id @0 :UInt64;
  expression @1 :Text;  # SMT-LIB format
}

struct Proof {
  format @0 :ProofFormat;
  data @1 :Data;  # Alethe proof bytes
  verified @2 :Bool;
  
  enum ProofFormat {
    alethe @0;
    z3Native @1;
  }
}

struct VerificationResult {
  union {
    verified :group {
      proof @0 :Proof;
      action @1 :AgentAction;
    }
    rejected :group {
      reason @2 :Text;
      feedback @3 :Text;  # For AutoRocq-style refinement
    }
    inconclusive @4 :Text;
  }
}

interface ConstraintEngine {
  # Verify an action against constraints
  verify @0 (action :AgentAction, constraints :List(Constraint))
         -> (result :VerificationResult);
  
  # Add a constraint to the system
  addConstraint @1 (constraint :Constraint) -> ();
  
  # Get current constraint set
  getConstraints @2 () -> (constraints :List(Constraint));
}

interface VerifiedAgent {
  # Propose an action (returns capability to execute if verified)
  proposeAction @0 (action :AgentAction)
                -> (executor :ActionExecutor);
  
  # Refine action based on feedback
  refineAction @1 (original :AgentAction, feedback :Text)
               -> (refined :AgentAction);
}

interface ActionExecutor {
  # Execute the verified action (capability-secured)
  execute @0 () -> (result :AnyPointer);
  
  # Get the proof for this action
  getProof @1 () -> (proof :Proof);
}

interface AuditLog {
  # Record an executed action with proof
  record @0 (action :AgentAction, proof :Proof, result :AnyPointer) -> ();
  
  # Query audit history
  query @1 (filter :AuditFilter) -> (entries :List(AuditEntry));
  
  struct AuditFilter {
    startTime @0 :UInt64;
    endTime @1 :UInt64;
    actionTypes @2 :List(AgentAction.ActionType);
  }
  
  struct AuditEntry {
    timestamp @0 :UInt64;
    action @1 :AgentAction;
    proof @2 :Proof;
    result @3 :AnyPointer;
  }
}
```

### Implementation: Rust + Elixir

#### Rust Side (capnp-rpc 0.25+)

```rust
// Cargo.toml
// capnp = "0.19"
// capnp-rpc = "0.19"

use capnp_rpc::{RpcSystem, twoparty, rpc_twoparty_capnp};

impl constraint_engine::Server for ConstraintEngineImpl {
    async fn verify(
        &self,
        params: constraint_engine::VerifyParams,
        mut results: constraint_engine::VerifyResults,
    ) -> Result<(), capnp::Error> {
        let action = params.get()?.get_action()?;
        let constraints = params.get()?.get_constraints()?;
        
        // Use Z3 NIF to verify
        let proof = self.z3_solver.verify(action, constraints).await?;
        
        let mut result = results.get().init_result();
        match proof {
            Ok(p) => {
                let mut verified = result.init_verified();
                verified.set_proof(p);
                verified.set_action(action);
            }
            Err(e) => {
                let mut rejected = result.init_rejected();
                rejected.set_reason(&e.reason);
                rejected.set_feedback(&e.feedback);
            }
        }
        
        Ok(())
    }
}
```

#### Elixir Side (ecapnp)

```elixir
# Using ecapnp for Cap'n Proto in Elixir
defmodule Synapsix.Protocol.Client do
  @moduledoc """
  Cap'n Proto RPC client for Synapsix protocol.
  """
  
  def connect(host, port) do
    {:ok, socket} = :gen_tcp.connect(host, port, [:binary, packet: 4])
    {:ok, %{socket: socket, pending: %{}}}
  end
  
  def verify_action(client, action, constraints) do
    request = Synapsix.Protocol.Schema.ConstraintEngine.verify(
      action: action,
      constraints: constraints
    )
    
    case send_request(client, request) do
      {:ok, result} ->
        case result.union do
          {:verified, %{proof: proof, action: action}} ->
            {:verified, proof, action}
          {:rejected, %{reason: reason, feedback: feedback}} ->
            {:rejected, reason, feedback}
          {:inconclusive, reason} ->
            {:inconclusive, reason}
        end
      {:error, reason} ->
        {:error, reason}
    end
  end
end
```

### Protocol Levels

Cap'n Proto RPC has feature levels:

| Level | Feature | Synapsix Use |
|-------|---------|--------------|
| **1** | Object refs + pipelining | Core verification loop |
| **2** | Persistent capabilities | Save verified action tokens |
| **3** | Three-way connections | Distributed verification |
| **4** | Reference equality | Multi-party verification |

For Synapsix Phase 1, we need Level 1. Level 2+ for distributed deployment.

### Comparison with Alternatives

| Protocol | Zero-Copy | Pipelining | Capabilities | Our Fit |
|----------|-----------|------------|--------------|---------|
| **Cap'n Proto** | ✅ | ✅ | ✅ | ⭐⭐⭐⭐⭐ |
| **Flatbuffers** | ✅ | ❌ | ❌ | ⭐⭐⭐ |
| **gRPC/Protobuf** | ❌ | ❌ | ❌ | ⭐⭐ |
| **MCP/JSON-RPC** | ❌ | ❌ | ❌ | ⭐ |

**Verdict**: Cap'n Proto is ideal for Synapsix NeSy protocol.

## OpenSSA / DANA: Domain-Aware Neurosymbolic Agents

**Research Date**: January 31, 2026

### What is DANA?

**Domain-Aware Neurosymbolic Agent (DANA)** is an architecture that combines:
- Neural networks (LLMs) for pattern recognition
- Symbolic systems for deterministic precision
- Domain expert knowledge as first-class citizen

Implemented in the **OpenSSA** framework (Python, Apache 2.0).

### DANA Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    DANA ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           DOMAIN KNOWLEDGE LAYER                     │    │
│  │  • Expert rules    • Procedures    • Constraints     │    │
│  │  • Domain ontology • Best practices                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                           ▼                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           HIERARCHICAL TASK PLANNING (HTP)           │    │
│  │  • Goal decomposition   • Plan synthesis             │    │
│  │  • Task scheduling      • Resource allocation        │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                           ▼                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           OODAR REASONING ENGINE                     │    │
│  │  Observe → Orient → Decide → Act → Repeat            │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                           ▼                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           NEURAL EXECUTION LAYER                     │    │
│  │  • LLM inference (can use small models)              │    │
│  │  • Pattern matching   • Generation                   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Key Concepts

#### 1. Hierarchical Task Planning (HTP)

Decomposes complex problems into manageable subtasks:

```
Goal: Optimize plasma etching recipe
├── Subtask 1: Analyze current parameters
│   ├── Read sensor data
│   └── Compare to baseline
├── Subtask 2: Identify optimization targets
│   ├── Etch rate
│   ├── Selectivity
│   └── Uniformity
├── Subtask 3: Generate candidate recipes
│   └── Apply domain constraints
└── Subtask 4: Validate and recommend
    ├── Simulate outcomes
    └── Rank alternatives
```

#### 2. OODAR Loop (Observe-Orient-Decide-Act-Repeat)

Continuous reasoning cycle:

| Phase | Action | Synapsix Parallel |
|-------|--------|-------------------|
| **Observe** | Gather current state | Read agent context |
| **Orient** | Interpret via domain knowledge | Apply constraints |
| **Decide** | Select action from plan | Z3 verification |
| **Act** | Execute with monitoring | Guarded execution |
| **Repeat** | Loop with feedback | Iterative refinement |

#### 3. Domain Knowledge First-Class

Unlike generic agents, DANA treats domain expertise as fundamental:

- **Expert Rules**: Formalized best practices
- **Procedures**: Step-by-step workflows
- **Constraints**: Hard boundaries that must not be violated
- **Ontology**: Domain-specific vocabulary and relationships

### Performance Results

| Metric | Generic LLM Agents | DANA |
|--------|-------------------|------|
| Accuracy (hard problems) | <50% | **90%+** |
| Consistency | Low (varies by run) | **High** |
| Model size required | Large (GPT-4 class) | **Small (7B-14B)** |
| Domain adaptation | Poor | **Excellent** |

### Industrial Use Cases

1. **Semiconductor Manufacturing**
   - Plasma etching recipe optimization
   - Process parameter analysis
   - Quality defect prediction

2. **Marine Electronics**
   - Equipment troubleshooting
   - Knowledge transfer from retiring experts
   - 3-4x faster issue resolution

3. **Railway Operations**
   - Culturally-aware service recommendations
   - Dynamic scheduling guidance
   - Passenger experience optimization

### Relevance to Synapsix

DANA concepts that apply directly to Synapsix NeSy:

| DANA Concept | Synapsix Application |
|--------------|---------------------|
| Domain Knowledge | Safety constraints, user policies |
| HTP | Agent task decomposition |
| OODAR | Continuous constraint evaluation |
| Symbolic precision | Z3 verification |
| Small model support | Cost-effective local inference |

### Synapsix vs DANA Comparison

| Aspect | DANA/OpenSSA | Synapsix NeSy |
|--------|--------------|---------------|
| **Focus** | Industrial problem-solving | Agent security & verification |
| **Symbolic Layer** | Domain rules + HTP | SMT constraints + formal proofs |
| **Verification** | Heuristic validation | Z3 + Carcara proof checking |
| **Implementation** | Python | Elixir + Rust NIFs |
| **Protocol** | Internal | Cap'n Proto (NOT-MCP) |
| **Proof trail** | Limited | Full Alethe proofs |

### What Synapsix Can Learn from DANA

1. **Hierarchical Task Planning**
   - Decompose agent actions into verifiable subtasks
   - Each subtask gets its own constraint scope

2. **OODAR for Continuous Monitoring**
   - Don't just verify once - continuous observation
   - Orient actions to current constraint state

3. **Domain Knowledge Integration**
   - User-defined safety policies as first-class
   - Export/import knowledge across deployments

4. **Small Model Efficiency**
   - Symbolic layer does the heavy lifting
   - LLM only needed for interpretation/generation

### Integration Possibility

Could use OpenSSA's planning layer with Synapsix's verification layer:

```
┌───────────────────────────────────────────────────────────┐
│                   HYBRID ARCHITECTURE                      │
├───────────────────────────────────────────────────────────┤
│                                                            │
│  ┌─────────────────┐      ┌─────────────────┐            │
│  │    OpenSSA      │      │    Synapsix     │            │
│  │   DANA Agent    │◄────►│   NeSy Engine   │            │
│  │                 │      │                 │            │
│  │  • HTP planning │      │  • Z3 verify    │            │
│  │  • OODAR loop   │      │  • Proof gen    │            │
│  │  • Domain KB    │      │  • Cap'n Proto  │            │
│  └─────────────────┘      └─────────────────┘            │
│                                                            │
│  OpenSSA proposes actions → Synapsix verifies → Execute  │
│                                                            │
└───────────────────────────────────────────────────────────┘
```

This hybrid could combine DANA's proven industrial accuracy with Synapsix's formal verification guarantees.

## neuro-san: Multi-Agent Network Architecture

**Research Date**: January 31, 2026

### What is neuro-san?

**Neuro AI System of Agent Networks (neuro-san)** is Cognizant's open-source framework for building data-driven multi-agent networks.

**License**: Apache-2.0  
**Language**: Python  
**Stars**: 88 (growing)

### Core Philosophy

> "People expect the equivalent of an adult PhD to be at their disposal, but what you really get is a high-school intern."

**Solution**: Break problems into smaller pieces so multiple specialized agents can collaborate.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 NEURO-SAN ARCHITECTURE                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│                    ┌─────────────────┐                       │
│                    │   Front Man     │  User-facing agent    │
│                    │     Agent       │  Coordinates network  │
│                    └────────┬────────┘                       │
│                             │                                │
│           ┌─────────────────┼─────────────────┐              │
│           │                 │                 │              │
│           ▼                 ▼                 ▼              │
│   ┌───────────────┐ ┌───────────────┐ ┌───────────────┐     │
│   │  Specialist   │ │  Specialist   │ │  Specialist   │     │
│   │   Agent A     │ │   Agent B     │ │   Agent C     │     │
│   └───────┬───────┘ └───────┬───────┘ └───────┬───────┘     │
│           │                 │                 │              │
│           ▼                 ▼                 ▼              │
│   ┌───────────────┐ ┌───────────────┐ ┌───────────────┐     │
│   │  CodedTool    │ │  CodedTool    │ │    Sub-       │     │
│   │  (Python)     │ │  (Python)     │ │   Agents      │     │
│   └───────────────┘ └───────────────┘ └───────────────┘     │
│                                                              │
│   HOCON Configuration (Data-Driven, No-Code)                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Key Features

#### 1. HOCON-Based Agent Definition

Agents defined entirely in data files (HOCON = JSON with comments):

```hocon
// hello_world.hocon
{
  name: "hello_world"
  description: "A simple greeting agent network"
  
  agents: [
    {
      name: "greeter"
      role: "front_man"
      llm: "gpt-4o"
      instructions: """
        You are a friendly greeter.
        When someone wants to send a greeting, 
        delegate to the message_composer agent.
      """
      downstream_agents: ["message_composer"]
    }
    {
      name: "message_composer"
      llm: "gpt-3.5-turbo"  // Cheaper model for simple task
      instructions: """
        Compose a short greeting message based on the request.
      """
    }
  ]
}
```

#### 2. CodedTools for Deterministic Operations

When LLMs can't be trusted:

```python
# coded_tools/hello_world/api_caller.py
from neuro_san.interfaces.coded_tool import CodedTool

class ApiCaller(CodedTool):
    async def async_invoke(
        self, 
        args: Dict[str, Any], 
        sly_data: Dict[str, Any]
    ) -> Any:
        # Deterministic Python code
        # - API calls
        # - Complex math
        # - Data copying without errors
        # - Secrets management
        api_key = sly_data.get("api_key")  # Never in LLM context!
        return await call_external_api(args["endpoint"], api_key)
```

#### 3. Sly Data (Private Data Channels)

Data that **never enters LLM chat streams**:

```python
# Client sends sly_data
session.streaming_chat(
    message="Check my account balance",
    sly_data={
        "user_id": "12345",
        "auth_token": "secret_token",  # Never seen by LLM!
        "session_id": "abc123"
    }
)
```

Use cases:
- User credentials
- API tokens
- Session state
- Bulletin board for CodedTool cooperation

#### 4. Agent-Specific LLM Configuration

Use the right model for each task:

```hocon
{
  agents: [
    {
      name: "complex_reasoner"
      llm: "gpt-4o"  // Expensive but capable
      fallback_llm: "claude-3-sonnet"  // If GPT-4 is down
    }
    {
      name: "simple_formatter"
      llm: "gpt-3.5-turbo"  // Cheap for simple tasks
    }
    {
      name: "local_private"
      llm: "ollama/llama3"  // Local for privacy
    }
  ]
}
```

### Relevance to Synapsix

| neuro-san Feature | Synapsix Application |
|-------------------|---------------------|
| Sly data channels | Private constraint contexts |
| CodedTools | Z3 solver, Carcara verification |
| Agent graphs | Verification pipeline stages |
| HOCON config | Elixir DSL alternative |
| Fallback LLMs | Fault-tolerant inference |
| MCP protocol | Compare with our Cap'n Proto |

### What Synapsix Can Learn

#### 1. Private Data Channels

neuro-san's `sly_data` pattern is essential for security:

```elixir
defmodule Synapsix.SlyData do
  @moduledoc """
  Private data that never enters LLM context.
  Inspired by neuro-san's sly_data.
  """
  
  defstruct [
    :user_credentials,
    :api_tokens,
    :constraint_secrets,
    :verification_context
  ]
  
  def extract_for_verification(%__MODULE__{} = sly) do
    # Only expose what's needed for constraint checking
    %{
      user_permissions: sly.user_credentials.permissions,
      # Token values never exposed, only presence
      has_valid_token: !is_nil(sly.api_tokens.current)
    }
  end
end
```

#### 2. Hybrid LLM/Deterministic Architecture

The CodedTool pattern aligns with our Z3 NIF approach:

```elixir
defmodule Synapsix.VerificationTool do
  @behaviour Synapsix.CodedTool
  
  @impl true
  def invoke(args, sly_data) do
    # Deterministic Z3 verification - NOT an LLM call!
    constraints = args["constraints"]
    context = SlyData.extract_for_verification(sly_data)
    
    case Synapsix.NeSy.Z3Nif.verify(constraints, context) do
      {:sat, model} -> {:ok, %{verified: true, model: model}}
      {:unsat, proof} -> {:ok, %{verified: false, proof: proof}}
    end
  end
end
```

#### 3. Agent Graph Topology

neuro-san's directed graph maps well to verification pipelines:

```
┌─────────────────────────────────────────────────────────────┐
│           SYNAPSIX VERIFICATION PIPELINE                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────┐                                           │
│   │  Action     │◄── Agent proposes action                  │
│   │  Proposer   │                                           │
│   └──────┬──────┘                                           │
│          │                                                   │
│          ▼                                                   │
│   ┌─────────────┐                                           │
│   │ Constraint  │◄── CodedTool: Z3 NIF                      │
│   │  Checker    │                                           │
│   └──────┬──────┘                                           │
│          │                                                   │
│          ├──────────────┐                                    │
│          │              │                                    │
│          ▼              ▼                                    │
│   ┌─────────────┐ ┌─────────────┐                           │
│   │   Proof     │ │  Refinement │◄── If rejected            │
│   │  Generator  │ │    Agent    │                           │
│   └──────┬──────┘ └──────┬──────┘                           │
│          │              │                                    │
│          │              └──────────────┐                     │
│          ▼                             │                     │
│   ┌─────────────┐                      │                     │
│   │   Proof     │◄── CodedTool: Carcara                     │
│   │  Verifier   │                                           │
│   └──────┬──────┘                      │                     │
│          │                             │                     │
│          ▼                             ▼                     │
│   ┌─────────────┐               ┌─────────────┐             │
│   │  Executor   │               │   Back to   │             │
│   │  (Guarded)  │               │  Proposer   │             │
│   └─────────────┘               └─────────────┘             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### MCP vs Cap'n Proto Comparison

neuro-san supports MCP protocol. How does it compare to our Cap'n Proto choice?

| Aspect | MCP (neuro-san) | Cap'n Proto (Synapsix) |
|--------|-----------------|------------------------|
| **Transport** | JSON-RPC over HTTP/stdio | Binary over TCP/Unix |
| **Speed** | Moderate | Very fast (zero-copy) |
| **Schema** | Runtime | Compile-time |
| **Capabilities** | Implicit trust | First-class |
| **Pipelining** | No | Yes |
| **Ecosystem** | Growing (Cursor, etc) | Established |
| **Security model** | Token-based | Capability-based |

**Conclusion**: Cap'n Proto is better for Synapsix's security-critical verification, but we could offer MCP as a compatibility layer.

### Implementation Ideas

1. **Port sly_data to Elixir**
   - Dedicated process for private data
   - Never serialized to wire
   - Erlang process isolation

2. **HOCON alternative in Elixir**
   - Use Elixir's native term format
   - Or build a DSL compiler

3. **MCP compatibility layer**
   - Translate MCP to Cap'n Proto
   - Support existing MCP tools

## Synapsix NeSy Implementation Plan

**Created**: January 31, 2026  
**Status**: Planning

### Overview

A phased implementation plan for the Synapsix Neurosymbolic AI stack, built on our preferred technologies (Elixir, Rust, Nix) with SSPL/AGPL licensing.

### Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                    SYNAPSIX NESY STACK                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    APPLICATION LAYER                       │  │
│  │  • Cursor Agent Integration                                │  │
│  │  • Dialog System (synapsix-dialog-daemon)                 │  │
│  │  • Harnesses (AI agent controllers)                       │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │                   ORCHESTRATION LAYER                      │  │
│  │  • Elixir/OTP GenServers                                  │  │
│  │  • Agent Pipeline Supervisors                             │  │
│  │  • Sly Data Isolation (inspired by neuro-san)             │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │                   VERIFICATION LAYER                       │  │
│  │  • Constraint DSL (Elixir macros)                         │  │
│  │  • Z3 NIF (Rustler)                                       │  │
│  │  • Carcara NIF (proof verification)                       │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │                   PROTOCOL LAYER                           │  │
│  │  • Cap'n Proto RPC (NOT-MCP)                              │  │
│  │  • Capability-based security                               │  │
│  │  • Promise pipelining                                      │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │                   FORMAL VERIFICATION                      │  │
│  │  • Quint specifications                                    │  │
│  │  • Apalache/Z3 model checking                             │  │
│  │  • Property testing from specs                            │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

### Phase 1: Foundation (Core NIFs)

**Goal**: Establish Rust NIF infrastructure for Z3 and Carcara.

#### 1.1 Z3 Rustler NIF

```
Deliverables:
├── native/synapsix_z3/
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs           # NIF entry points
│       ├── context.rs       # Z3 context management
│       ├── solver.rs        # SAT/SMT solving
│       └── proof.rs         # Proof extraction
└── lib/synapsix/nesy/z3_nif.ex
```

**Functions to implement**:
- `create_context/0` → `ResourceArc<Z3Context>`
- `add_constraint/2` → `:ok | {:error, reason}`
- `check_sat/1` → `:sat | :unsat | :unknown`
- `get_model/1` → `%{variable => value}`
- `get_proof/1` → `{:ok, proof_bytes} | {:error, :no_proof}`
- `dispose_context/1` → `:ok`

**Schedule flags**: All use `DirtyCpu` except `dispose_context`.

#### 1.2 Carcara Rustler NIF

```
Deliverables:
├── native/synapsix_carcara/
│   ├── Cargo.toml
│   └── src/
│       └── lib.rs           # Proof verification NIF
└── lib/synapsix/nesy/carcara_nif.ex
```

**Functions to implement**:
- `verify_proof/2` → `{boolean, message}`
- `verify_proof_parallel/3` → `{boolean, message}`
- `elaborate_proof/2` → `{:ok, elaborated_proof} | {:error, reason}`

#### 1.3 Test Suite

- Property-based tests with StreamData
- Known SAT/UNSAT problems from SMTLIB benchmarks
- Proof verification roundtrips

**Estimated effort**: 2-3 weeks

---

### Phase 2: Constraint DSL

**Goal**: Elixir DSL for defining safety constraints.

#### 2.1 DSL Design

```elixir
defmodule Synapsix.Constraints do
  use Synapsix.Constraint.DSL
  
  # Define constraint schema
  defconstraint :file_access do
    # Variables
    var :agent_id, :string
    var :path, :string
    var :operation, {:enum, [:read, :write, :delete]}
    
    # Constraints (compiles to SMT-LIB)
    constraint :no_etc_passwd do
      path != "/etc/passwd"
    end
    
    constraint :no_home_write do
      not (starts_with?(path, "/home/") and operation == :write)
    end
    
    constraint :allowed_paths do
      one_of(path, [
        starts_with?("/tmp/"),
        starts_with?("/var/log/"),
        @agent_allowed_paths  # Runtime injection
      ])
    end
  end
end
```

#### 2.2 Compiler Implementation

```elixir
defmodule Synapsix.Constraint.Compiler do
  @moduledoc """
  Compiles Elixir constraint DSL to SMT-LIB format.
  """
  
  def compile(ast) do
    ast
    |> normalize()
    |> type_check()
    |> emit_smtlib()
  end
end
```

#### 2.3 Runtime Evaluation

```elixir
defmodule Synapsix.Constraint.Evaluator do
  @moduledoc """
  Evaluates constraints against proposed actions.
  """
  
  def evaluate(constraints, action, context) do
    # 1. Compile constraints to SMT-LIB
    smtlib = Compiler.compile(constraints)
    
    # 2. Add action parameters as assertions
    smtlib_with_action = add_action_assertions(smtlib, action)
    
    # 3. Check with Z3
    case Z3Nif.check_sat(smtlib_with_action) do
      :sat -> {:verified, get_proof(smtlib_with_action)}
      :unsat -> {:rejected, get_counterexample(smtlib_with_action)}
      :unknown -> {:timeout, nil}
    end
  end
end
```

**Estimated effort**: 2-3 weeks

---

### Phase 3: Cap'n Proto Protocol

**Goal**: Implement NOT-MCP communication protocol.

#### 3.1 Schema Definition

```capnp
# synapsix_protocol.capnp
@0x9eb32e19f86ee174;

# Core types
struct Action { ... }
struct Constraint { ... }
struct Proof { ... }
struct VerificationResult { ... }

# Service interfaces
interface ConstraintEngine { ... }
interface VerifiedAgent { ... }
interface ActionExecutor { ... }
interface AuditLog { ... }
```

#### 3.2 Rust Implementation

Using `capnp-rpc` crate for the Rust side.

#### 3.3 Elixir Integration

Using `ecapnp` or building a NIF bridge.

**Estimated effort**: 3-4 weeks

---

### Phase 4: Orchestration Layer

**Goal**: Elixir/OTP supervision tree for agent verification.

#### 4.1 Core Components

```elixir
# lib/synapsix/nesy/supervisor.ex
defmodule Synapsix.NeSy.Supervisor do
  use Supervisor
  
  def start_link(opts) do
    Supervisor.start_link(__MODULE__, opts, name: __MODULE__)
  end
  
  def init(_opts) do
    children = [
      # Z3 context pool (reuse expensive contexts)
      {Synapsix.NeSy.ContextPool, []},
      
      # Verification pipeline
      {Synapsix.NeSy.VerificationPipeline, []},
      
      # Proof cache
      {Synapsix.NeSy.ProofCache, []},
      
      # Audit logger
      {Synapsix.NeSy.AuditLog, []},
      
      # Sly data registry (private data isolation)
      {Synapsix.NeSy.SlyDataRegistry, []}
    ]
    
    Supervisor.init(children, strategy: :one_for_one)
  end
end
```

#### 4.2 Verification Pipeline

```elixir
defmodule Synapsix.NeSy.VerificationPipeline do
  use GenStage
  
  # Stages:
  # 1. ActionReceiver - accepts proposed actions
  # 2. ConstraintLoader - loads relevant constraints
  # 3. Verifier - Z3 SAT checking
  # 4. ProofGenerator - extract/generate proof
  # 5. ProofVerifier - Carcara verification
  # 6. Executor - guarded action execution
end
```

#### 4.3 Sly Data Isolation

```elixir
defmodule Synapsix.NeSy.SlyData do
  @moduledoc """
  Private data that never enters LLM context.
  Inspired by neuro-san's sly_data pattern.
  """
  
  use Agent
  
  defstruct [
    :session_id,
    :user_credentials,
    :api_tokens,
    :constraint_secrets,
    bulletin_board: %{}  # For inter-tool cooperation
  ]
  
  # Process-isolated storage
  def start_link(session_id, initial_data) do
    Agent.start_link(
      fn -> struct(__MODULE__, Map.put(initial_data, :session_id, session_id)) end,
      name: via_tuple(session_id)
    )
  end
  
  # Only expose what verification needs
  def for_verification(session_id) do
    Agent.get(via_tuple(session_id), fn sly ->
      %{
        permissions: sly.user_credentials.permissions,
        has_valid_token: !is_nil(sly.api_tokens.current)
      }
    end)
  end
end
```

**Estimated effort**: 3-4 weeks

---

### Phase 5: Formal Specification

**Goal**: Quint specs for protocol correctness.

#### 5.1 Protocol Specification

```quint
// synapsix_protocol.qnt
module SynapsixProtocol {
  // State machine for action verification
  // Properties: safety, liveness, consistency
}
```

#### 5.2 Apalache Verification

```bash
quint verify synapsix_protocol.qnt --invariant=safety
```

#### 5.3 Property Testing Integration

Generate test traces from Quint specs, run against implementation.

**Estimated effort**: 2 weeks

---

### Phase 6: Integration

**Goal**: Connect NeSy stack to existing Synapsix components.

#### 6.1 Dialog System Integration

```elixir
# When dialog requests action, verify first
defmodule Synapsix.Dialog.VerifiedHandler do
  def handle_action_request(action, sly_data) do
    case Synapsix.NeSy.verify(action, sly_data) do
      {:verified, proof} ->
        {:ok, execute_with_proof(action, proof)}
      {:rejected, reason} ->
        {:error, :constraint_violation, reason}
    end
  end
end
```

#### 6.2 Harness Integration

```elixir
# Harnesses use NeSy for all agent actions
defmodule Synapsix.Harness.NeSyBridge do
  def before_action(harness, action) do
    Synapsix.NeSy.propose(action, harness.sly_data)
  end
  
  def after_action(harness, action, result, proof) do
    Synapsix.NeSy.audit(action, result, proof)
  end
end
```

**Estimated effort**: 2 weeks

---

### Phase 7: Precompilation & Distribution

**Goal**: Cross-platform NIF distribution.

#### 7.1 rustler_precompiled Setup

```elixir
# mix.exs
defp deps do
  [
    {:rustler_precompiled, "~> 0.8"}
  ]
end
```

#### 7.2 GitHub Actions CI

Build for all targets:
- x86_64-unknown-linux-gnu
- aarch64-unknown-linux-gnu
- x86_64-apple-darwin
- aarch64-apple-darwin
- x86_64-pc-windows-msvc

#### 7.3 Hex Package Release

Publish with precompiled binaries to Hex.

**Estimated effort**: 1 week

---

### Timeline Summary

| Phase | Description | Effort | Cumulative |
|-------|-------------|--------|------------|
| 1 | Foundation (NIFs) | 2-3 weeks | 2-3 weeks |
| 2 | Constraint DSL | 2-3 weeks | 4-6 weeks |
| 3 | Cap'n Proto | 3-4 weeks | 7-10 weeks |
| 4 | Orchestration | 3-4 weeks | 10-14 weeks |
| 5 | Formal Specs | 2 weeks | 12-16 weeks |
| 6 | Integration | 2 weeks | 14-18 weeks |
| 7 | Distribution | 1 week | **15-19 weeks** |

### Dependencies Between Phases

```
Phase 1 (NIFs) ─────────────┬───► Phase 4 (Orchestration)
                            │
Phase 2 (DSL) ──────────────┤
                            │
Phase 3 (Protocol) ─────────┴───► Phase 6 (Integration)
                            │
Phase 5 (Formal) ───────────┘
                            │
                            ▼
                    Phase 7 (Distribution)
```

Phases 1, 2, 3, and 5 can proceed in **parallel**.

### Licensing Strategy

| Component | License | Rationale |
|-----------|---------|-----------|
| NeSy Core (NIFs, DSL) | SSPL | Infrastructure protection |
| Orchestration | AGPL | Strong copyleft |
| Protocol Schema | Apache-2.0 | Encourage adoption |
| Quint Specs | Apache-2.0 | Community contribution |

### Success Metrics

1. **Correctness**: All Quint properties verified by Apalache
2. **Performance**: Verification <100ms for typical constraints
3. **Coverage**: 100% of agent actions verified
4. **Proofs**: All executed actions have verifiable proofs
5. **Distribution**: Precompiled for 5 major platforms

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Z3 NIF complexity | Start with minimal API, expand iteratively |
| Cap'n Proto Elixir support | Build NIF bridge if ecapnp insufficient |
| Performance bottleneck | Z3 context pooling, parallel verification |
| Adoption resistance | MCP compatibility layer for gradual migration |

## Resources

- neuro-san: https://github.com/cognizant-ai-lab/neuro-san
- OpenSSA: https://aitomatic.github.io/openssa
- Kouvaros formal verification paper: https://pkouvaros.github.io/IJCAI23-K/paper
- AutoRocq paper: https://arxiv.org/abs/2511.17330
- Z3 Rust bindings: https://github.com/prove-rs/z3.rs
- Z3 docs.rs: https://docs.rs/z3/latest/z3/
- ex_smt (Elixir): https://github.com/tsutsu/ex_smt
- Rustler: https://github.com/rusterlium/rustler
- Cap'n Proto: https://capnproto.org/
- TLA+: https://lamport.azurewebsites.net/tla/tla.html
- Quint: https://quint-lang.org/
- Apalache: https://apalache-mc.org/

## TLA+ / Quint: Formal Protocol Specification

**Research Date**: January 31, 2026

### Why Formal Specification?

Before implementing our Cap'n Proto-based NeSy protocol, we should formally verify:
- **Safety properties**: Actions can only execute if verified
- **Liveness properties**: Verification always terminates
- **Consensus**: Multi-node agreement on constraint evaluation
- **Protocol correctness**: Message ordering, error handling

### TLA+ vs Quint

| Aspect | TLA+ | Quint |
|--------|------|-------|
| **Syntax** | Mathematical notation | Modern, TypeScript-like |
| **Learning curve** | Steep | Gentle |
| **Tooling** | TLC, TLAPS | Simulator, Apalache |
| **IDE** | TLA+ Toolbox, VSCode | VSCode, CLI |
| **Backend** | TLC (explicit state) | Apalache (Z3 SMT!) |
| **License** | MIT | Apache 2.0 |

**Recommendation**: Use **Quint** for Synapsix because:
1. Engineer-friendly syntax
2. Uses Apalache/Z3 - same solver as our constraint engine!
3. Model-based testing support
4. Proven on production systems (ZKsync, Tendermint, Matter Labs)

### Apalache: The Connection to Z3

Apalache translates TLA+/Quint specs to SMT constraints for Z3:

```
┌─────────────────────────────────────────────────────────────┐
│           SYNAPSIX FORMAL VERIFICATION STACK                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌───────────────┐                                          │
│  │    Quint      │  Protocol specification                  │
│  │  (.qnt files) │  - Agent behavior                        │
│  └───────┬───────┘  - Verification rules                    │
│          │          - Safety invariants                     │
│          ▼                                                   │
│  ┌───────────────┐                                          │
│  │   Apalache    │  Symbolic model checking                 │
│  └───────┬───────┘  - Bounded model checking                │
│          │          - Inductiveness checking                │
│          ▼                                                   │
│  ┌───────────────┐                                          │
│  │      Z3       │  SMT solving                             │
│  └───────────────┘  (Same solver as runtime!)               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Quint Specification for Synapsix

```quint
// synapsix_nesy.qnt - Formal specification of NeSy protocol

module SynapsixNeSy {
  // Type definitions
  type AgentId = str
  type ActionId = int
  type Constraint = str  // SMT-LIB format
  type Proof = { format: str, data: str, verified: bool }
  
  type ActionType = FileRead | FileWrite | ShellExec | NetworkReq
  
  type Action = {
    id: ActionId,
    agent: AgentId,
    actionType: ActionType,
    target: str,
    verified: bool,
    proof: Proof
  }
  
  // State variables
  var pendingActions: Set[Action]
  var verifiedActions: Set[Action]
  var executedActions: Set[Action]
  var constraints: Set[Constraint]
  
  // Constants
  pure val AGENTS = Set("cursor_agent", "user_agent", "system_agent")
  
  // Initial state
  action init = all {
    pendingActions' = Set(),
    verifiedActions' = Set(),
    executedActions' = Set(),
    constraints' = Set(
      "(=> (= action_type \"file_write\") (has_permission agent \"write\"))",
      "(not (= target \"/etc/passwd\"))"
    )
  }
  
  // Agent proposes an action
  action proposeAction(agent: AgentId, actionType: ActionType, target: str) = all {
    val newAction = {
      id: size(pendingActions) + size(verifiedActions) + size(executedActions),
      agent: agent,
      actionType: actionType,
      target: target,
      verified: false,
      proof: { format: "none", data: "", verified: false }
    }
    pendingActions' = pendingActions.union(Set(newAction)),
    verifiedActions' = verifiedActions,
    executedActions' = executedActions,
    constraints' = constraints
  }
  
  // Verification succeeds (non-deterministic for model checking)
  action verifySuccess(actionId: ActionId) = all {
    val action = pendingActions.filter(a => a.id == actionId).fold(
      { id: -1, agent: "", actionType: FileRead, target: "", verified: false, 
        proof: { format: "none", data: "", verified: false } },
      (acc, a) => a
    )
    action.id >= 0,  // Guard: action exists
    val verifiedAction = { ...action, verified: true, 
      proof: { format: "alethe", data: "PROOF_DATA", verified: true } }
    pendingActions' = pendingActions.exclude(Set(action)),
    verifiedActions' = verifiedActions.union(Set(verifiedAction)),
    executedActions' = executedActions,
    constraints' = constraints
  }
  
  // Execute a verified action
  action executeAction(actionId: ActionId) = all {
    val action = verifiedActions.filter(a => a.id == actionId).fold(
      { id: -1, agent: "", actionType: FileRead, target: "", verified: false,
        proof: { format: "none", data: "", verified: false } },
      (acc, a) => a
    )
    action.id >= 0,        // Guard: action exists
    action.verified,       // Guard: must be verified
    action.proof.verified, // Guard: proof must be valid
    pendingActions' = pendingActions,
    verifiedActions' = verifiedActions.exclude(Set(action)),
    executedActions' = executedActions.union(Set(action)),
    constraints' = constraints
  }
  
  // Safety invariant: Only verified actions can be executed
  val safetyInvariant = executedActions.forall(a => a.verified and a.proof.verified)
  
  // Safety invariant: No action targeting /etc/passwd
  val noPasswdAccess = executedActions.forall(a => a.target != "/etc/passwd")
  
  // Liveness hint: Eventually actions get processed
  temporal eventuallyProcessed = always(
    pendingActions.size() > 0 implies eventually(pendingActions.size() == 0)
  )
}
```

### Running Quint Verification

```bash
# Install Quint
npm install -g @informalsystems/quint

# Type check
quint typecheck synapsix_nesy.qnt

# Run simulator (random execution)
quint run synapsix_nesy.qnt --invariant=safetyInvariant

# Run model checker (exhaustive, uses Apalache/Z3)
quint verify synapsix_nesy.qnt --invariant=safetyInvariant

# Generate test traces for implementation testing
quint run synapsix_nesy.qnt --out-itf=traces.json
```

### Model-Based Testing with Quint Connect

Quint Connect enables model-based testing in Rust:

```rust
// Use Quint-generated traces to test Rust implementation
use quint_connect::TestHarness;

#[test]
fn test_synapsix_protocol() {
    let harness = TestHarness::from_quint("synapsix_nesy.qnt");
    
    for trace in harness.generate_traces(100) {
        let mut system = SynapsixSystem::new();
        
        for step in trace.steps() {
            match step.action() {
                "proposeAction" => {
                    let result = system.propose_action(
                        step.get("agent"),
                        step.get("actionType"),
                        step.get("target"),
                    );
                    assert!(result.is_ok());
                }
                "verifySuccess" => {
                    let result = system.verify(step.get("actionId"));
                    assert!(result.is_ok());
                }
                "executeAction" => {
                    let result = system.execute(step.get("actionId"));
                    assert!(result.is_ok());
                    // Check safety invariant
                    assert!(system.last_executed().is_verified());
                }
                _ => {}
            }
        }
    }
}
```

### Industrial Use of TLA+/Quint

| Company | Use Case | Tool |
|---------|----------|------|
| **Amazon** | S3, DynamoDB, EC2 | TLA+ |
| **Microsoft** | Azure CosmosDB | TLA+ |
| **Matter Labs** | ZKsync governance, ChonkyBFT | Quint |
| **Cosmos** | Tendermint consensus, IBC | TLA+/Quint |
| **Aztec** | Governance protocol | Quint |
| **Informal** | Malachite consensus | Quint |

### Integration with Synapsix

1. **Specification Phase**: Write protocol in Quint
2. **Verification Phase**: Use Apalache/Z3 to check properties
3. **Testing Phase**: Generate traces with Quint, test with Rust
4. **Runtime Phase**: Use same Z3 constraints for live verification

This creates a **unified formal reasoning stack** from design to runtime!

## Rustler: Elixir-Rust NIF Integration

**Research Date**: January 31, 2026

### Why Rustler for Synapsix NIFs?

For our Z3 and Carcara integrations, we need:
- Safe Rust code that can't crash the BEAM
- Efficient data transfer (zero-copy where possible)
- Dirty scheduler support for CPU-intensive SMT solving
- Cross-platform precompiled binaries

Rustler provides all of this.

### Rustler Overview

**Version**: 0.37.2  
**License**: Apache-2.0 / MIT  
**Status**: Production-ready, widely used

```
┌─────────────────────────────────────────────────────────────┐
│                    RUSTLER ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐   │
│  │   Elixir    │     │   Rustler   │     │    Rust     │   │
│  │   Module    │◄───►│   Bridge    │◄───►│    Code     │   │
│  │             │     │             │     │             │   │
│  │ - NIF stubs │     │ - Encoding  │     │ - Z3 calls  │   │
│  │ - API       │     │ - Decoding  │     │ - Carcara   │   │
│  │             │     │ - Scheduler │     │             │   │
│  └─────────────┘     └─────────────┘     └─────────────┘   │
│                                                              │
│  BEAM VM                                 Native Code         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Key Rustler Concepts

#### 1. NIF Function Definition

```rust
use rustler::{Env, Term, NifResult};

#[rustler::nif]
fn verify_constraint(constraint: String, context: Term) -> NifResult<bool> {
    // Fast operation - use default scheduler
    let result = check_constraint(&constraint, &context)?;
    Ok(result)
}
```

#### 2. Dirty CPU Scheduler (Critical for SMT Solving!)

SMT solving with Z3 can take >1ms, so we MUST use dirty schedulers:

```rust
#[rustler::nif(schedule = "DirtyCpu")]
fn solve_constraints(
    constraints: Vec<String>,
    timeout_ms: u64,
) -> NifResult<SolveResult> {
    // CPU-intensive work - runs on dirty scheduler pool
    let solver = Z3Solver::new();
    for c in constraints {
        solver.add_constraint(&c)?;
    }
    
    match solver.check_with_timeout(timeout_ms) {
        SatResult::Sat => Ok(SolveResult::Sat(solver.get_model())),
        SatResult::Unsat => Ok(SolveResult::Unsat),
        SatResult::Unknown => Ok(SolveResult::Timeout),
    }
}
```

**Important**: Default BEAM has 1 dirty CPU scheduler per core. Don't exhaust them!

#### 3. Resource Types (For Persistent State)

Z3 contexts are expensive to create - use ResourceArc to keep them alive:

```rust
use rustler::{ResourceArc, resource};

#[derive(Resource)]
#[resource(name = "Z3Context")]
struct Z3ContextResource {
    context: z3::Context,
    config: z3::Config,
}

#[rustler::nif]
fn create_context() -> NifResult<ResourceArc<Z3ContextResource>> {
    let config = z3::Config::new();
    let context = z3::Context::new(&config);
    Ok(ResourceArc::new(Z3ContextResource { context, config }))
}

#[rustler::nif(schedule = "DirtyCpu")]
fn solve_with_context(
    ctx: ResourceArc<Z3ContextResource>,
    constraints: Vec<String>,
) -> NifResult<bool> {
    let solver = z3::Solver::new(&ctx.context);
    // ... use the persisted context
}
```

#### 4. Error Handling

```rust
use rustler::Error;

#[rustler::nif]
fn parse_constraint(input: String) -> NifResult<Constraint> {
    parse_smtlib(&input)
        .map_err(|e| Error::Term(Box::new(format!("Parse error: {}", e))))
}
```

### Elixir Side Integration

```elixir
defmodule Synapsix.NeSy.Z3Nif do
  use Rustler, 
    otp_app: :synapsix,
    crate: "synapsix_z3"
  
  # NIF stubs - will be replaced at load time
  def create_context(), do: :erlang.nif_error(:nif_not_loaded)
  def solve_constraints(_constraints, _timeout_ms), do: :erlang.nif_error(:nif_not_loaded)
  def solve_with_context(_ctx, _constraints), do: :erlang.nif_error(:nif_not_loaded)
end
```

### Precompilation with rustler_precompiled

For distribution without requiring Rust toolchain:

```elixir
# mix.exs
defp deps do
  [
    {:rustler, "~> 0.37", runtime: false},
    {:rustler_precompiled, "~> 0.8"}
  ]
end

# lib/synapsix/nesy/z3_nif.ex
defmodule Synapsix.NeSy.Z3Nif do
  version = Mix.Project.config()[:version]
  
  use RustlerPrecompiled,
    otp_app: :synapsix,
    crate: "synapsix_z3",
    base_url: "https://github.com/synapsix/releases/download/v#{version}",
    force_build: System.get_env("SYNAPSIX_BUILD") in ["1", "true"],
    targets: ~w(
      aarch64-apple-darwin
      x86_64-apple-darwin
      x86_64-unknown-linux-gnu
      aarch64-unknown-linux-gnu
      x86_64-pc-windows-msvc
    )
end
```

### GitHub Actions for Precompilation

```yaml
# .github/workflows/precompile.yml
name: Precompile NIFs

on:
  release:
    types: [published]

jobs:
  build:
    name: Build NIF (${{ matrix.nif }} - ${{ matrix.target }})
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            target: x86_64-unknown-linux-gnu
            nif: "2.16"
          - os: ubuntu-latest
            target: aarch64-unknown-linux-gnu
            nif: "2.16"
            use_cross: true
          - os: macos-latest
            target: x86_64-apple-darwin
            nif: "2.16"
          - os: macos-latest
            target: aarch64-apple-darwin
            nif: "2.16"
          - os: windows-latest
            target: x86_64-pc-windows-msvc
            nif: "2.16"
    
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: ${{ matrix.target }}
      
      - name: Build NIF
        run: |
          cd native/synapsix_z3
          cargo build --release --target ${{ matrix.target }}
      
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: nif-${{ matrix.target }}-${{ matrix.nif }}
          path: native/synapsix_z3/target/${{ matrix.target }}/release/*.so
```

### Best Practices for Synapsix NIFs

#### DO:
- ✅ Use `DirtyCpu` for any Z3/Carcara operation
- ✅ Use `ResourceArc` for long-lived solver contexts
- ✅ Return structured Elixir terms (atoms, tuples)
- ✅ Handle panics gracefully (Rustler catches them)
- ✅ Use rustler_precompiled for distribution
- ✅ Set reasonable timeouts for SMT solving

#### DON'T:
- ❌ Block on default schedulers for >1ms
- ❌ Create new Z3 contexts per call (expensive)
- ❌ Return raw pointers to Elixir
- ❌ Use `unsafe` unless absolutely necessary
- ❌ Forget to register resource types

### Performance Considerations

| Operation | Scheduler | Expected Time |
|-----------|-----------|---------------|
| Parse constraint | Default | <1ms |
| Simple SAT check | DirtyCpu | 1-100ms |
| Complex SMT solve | DirtyCpu | 100ms-10s |
| Proof verification | DirtyCpu | 10-500ms |
| Model extraction | DirtyCpu | 1-50ms |

### Complete NIF Module Structure

```
synapsix/
├── lib/
│   └── synapsix/
│       └── nesy/
│           ├── z3_nif.ex
│           └── carcara_nif.ex
├── native/
│   ├── synapsix_z3/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       └── lib.rs
│   └── synapsix_carcara/
│       ├── Cargo.toml
│       └── src/
│           └── lib.rs
└── mix.exs
```

This architecture provides:
1. **Safety**: Rust can't crash the BEAM
2. **Performance**: Dirty schedulers for SMT solving
3. **Efficiency**: ResourceArc for persistent contexts
4. **Distribution**: Precompiled for all platforms
