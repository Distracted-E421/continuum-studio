# Edge Computing & Custom DNS Research: Synapsix + Continuum Studio

> **Research Date**: January 2026  
> **Purpose**: Design cross-network service discovery and edge computing architecture  
> **Scope**: Custom DNS server, DNS-SD, k0s/k3s, global orchestration

---

## Executive Summary

| Component | Solution | Why |
|-----------|----------|-----|
| **Service Discovery** | Custom CoreDNS + DNS-SD | Cross-network, self-hosted, extensible |
| **Edge Kubernetes** | k0s (primary) or k3s | Single binary, CNCF certified, zero deps |
| **Cross-Network** | WireGuard mesh + DNS | No vendor lock-in, self-hosted |
| **Service Registry** | Custom Elixir service | BEAM-native, integrates with Synapsix |

**Key Insight**: Build a self-hosted, vendor-agnostic infrastructure that can scale from a single homelab to global edge deployment without relying on Tailscale, Consul, or other commercial dependencies.

---

## Part 1: Why Custom DNS for Continuum Studio?

### The Problem with Existing Solutions

| Solution | Issue |
|----------|-------|
| **mDNS/Avahi** | LAN-only, no cross-network capability |
| **Consul** | Commercial licensing, infrastructure overhead |
| **Tailscale/Headscale** | Vendor dependency, limited customization |
| **Kubernetes DNS** | Cluster-scoped, not cross-cluster friendly |

### What We Need

1. **Global reachability**: Control Synapsix harnesses from anywhere (phone, laptop on-the-go)
2. **No vendor lock-in**: Self-hosted, open source, no licensing fees
3. **Dynamic registration**: Services register/deregister automatically
4. **Edge-native**: Works on resource-constrained devices (Pi, ARM)
5. **Secure by default**: mTLS, authentication, encryption

### The Vision: Continuum DNS

```
┌─────────────────────────────────────────────────────────────────┐
│                    Continuum DNS Architecture                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────────────────────────────────────────────┐    │
│   │              CoreDNS (Custom Plugins)                  │    │
│   │                                                        │    │
│   │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐  │    │
│   │  │ synapsix    │ │ harness     │ │ studio          │  │    │
│   │  │ plugin      │ │ plugin      │ │ plugin          │  │    │
│   │  └─────────────┘ └─────────────┘ └─────────────────┘  │    │
│   │                                                        │    │
│   │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐  │    │
│   │  │ cache       │ │ forward     │ │ prometheus      │  │    │
│   │  │ (built-in)  │ │ (built-in)  │ │ (built-in)      │  │    │
│   │  └─────────────┘ └─────────────┘ └─────────────────┘  │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                              │                                  │
│                              ▼                                  │
│   ┌───────────────────────────────────────────────────────┐    │
│   │              Synapsix Service Registry                 │    │
│   │         (Elixir GenServer + ETS/DETS)                 │    │
│   │                                                        │    │
│   │  • Node registration/heartbeat                         │    │
│   │  • Harness discovery                                   │    │
│   │  • Health checking                                     │    │
│   │  • Cross-network coordination                          │    │
│   └───────────────────────────────────────────────────────┘    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Part 2: CoreDNS as Foundation

### Why CoreDNS?

CoreDNS is a DNS server written in Go, designed to be extensible through plugins:

| Feature | Benefit for Continuum |
|---------|----------------------|
| **Plugin architecture** | Add custom service discovery logic |
| **Single binary** | Easy to deploy on edge devices |
| **Multiple protocols** | DNS, DoT (DNS-over-TLS), DoH (DNS-over-HTTPS), gRPC |
| **CNCF graduated** | Production-ready, well-maintained |
| **Kubernetes default** | k0s/k3s use CoreDNS internally |
| **Prometheus integration** | Built-in metrics |

### CoreDNS Configuration Example

```
# Corefile for Continuum Studio

# Handle Synapsix service discovery
synapsix.local:53 {
    synapsix {
        registry http://localhost:4000/api/dns
        ttl 60
    }
    cache 30
    errors
    log
}

# Handle harness-specific queries
_harness._tcp.continuum.local:53 {
    template IN SRV {
        match _harness._tcp\.continuum\.local
        answer "{{ .Name }} 60 IN SRV 0 0 4369 synapsix.continuum.local."
        fallthrough
    }
}

# Forward everything else to upstream DNS
. {
    forward . 8.8.8.8 1.1.1.1
    cache 300
}
```

### Custom CoreDNS Plugin: `synapsix`

```go
// coredns-synapsix/synapsix.go
package synapsix

import (
    "context"
    "net/http"
    "github.com/coredns/coredns/plugin"
    "github.com/miekg/dns"
)

type Synapsix struct {
    Next     plugin.Handler
    Registry string  // URL of Synapsix service registry
    TTL      uint32
}

func (s Synapsix) ServeDNS(ctx context.Context, w dns.ResponseWriter, r *dns.Msg) (int, error) {
    // Extract query name
    qname := r.Question[0].Name
    qtype := r.Question[0].Qtype
    
    // Check if this is a Synapsix service query
    if isSynapsixQuery(qname) {
        // Query the Synapsix registry
        services, err := s.queryRegistry(qname, qtype)
        if err != nil {
            return plugin.NextOrFailure(s.Name(), s.Next, ctx, w, r)
        }
        
        // Build DNS response
        resp := buildResponse(r, services, s.TTL)
        w.WriteMsg(resp)
        return dns.RcodeSuccess, nil
    }
    
    // Pass to next plugin
    return plugin.NextOrFailure(s.Name(), s.Next, ctx, w, r)
}
```

---

## Part 3: DNS-SD (DNS-Based Service Discovery)

### RFC 6763 Overview

DNS-SD defines how to name and structure DNS records for service discovery:

```
Service Instance Name = <Instance> . <Service> . <Domain>

Example:
  "Obsidian Cursor"._synapsix-harness._tcp.continuum.local.
  
Where:
  Instance: "Obsidian Cursor" (human-readable)
  Service:  "_synapsix-harness._tcp" (service type)
  Domain:   "continuum.local." (where to look)
```

### DNS-SD Record Types

| Record | Purpose | Example |
|--------|---------|---------|
| **PTR** | List instances of a service type | `_synapsix-harness._tcp.continuum.local. PTR Obsidian\ Cursor._synapsix-harness._tcp.continuum.local.` |
| **SRV** | Find host and port for instance | `Obsidian\ Cursor._synapsix-harness._tcp.continuum.local. SRV 0 0 4369 obsidian.continuum.local.` |
| **TXT** | Additional metadata | `Obsidian\ Cursor._synapsix-harness._tcp.continuum.local. TXT "version=1.0" "harnesses=cursor,godot"` |

### Service Types for Continuum Studio

```
# Core service types
_synapsix._tcp          # Synapsix orchestrator nodes
_synapsix-harness._tcp  # Individual harnesses
_studio._tcp            # Continuum Studio UI instances
_dialog._tcp            # Dialog daemon instances
_agent-bridge._tcp      # AI provider bridges

# Subtypes for filtering
_cursor._sub._synapsix-harness._tcp    # Cursor IDE harnesses only
_godot._sub._synapsix-harness._tcp     # Godot harnesses only
_android._sub._synapsix-harness._tcp   # Android Studio harnesses
```

### DNS-SD Query Flow

```
┌──────────────┐                    ┌──────────────┐
│    Client    │                    │  CoreDNS +   │
│  (Studio UI) │                    │  Synapsix    │
└──────┬───────┘                    └──────┬───────┘
       │                                   │
       │  1. PTR query: _synapsix._tcp.continuum.local?
       │──────────────────────────────────►│
       │                                   │
       │  2. PTR responses:                │
       │     - Obsidian._synapsix._tcp...  │
       │     - neon-laptop._synapsix._tcp..│
       │◄──────────────────────────────────│
       │                                   │
       │  3. SRV query: Obsidian._synapsix._tcp...?
       │──────────────────────────────────►│
       │                                   │
       │  4. SRV response:                 │
       │     0 0 4369 obsidian.continuum.local
       │◄──────────────────────────────────│
       │                                   │
       │  5. TXT query: Obsidian._synapsix._tcp...?
       │──────────────────────────────────►│
       │                                   │
       │  6. TXT response:                 │
       │     "version=1.0" "harnesses=..."  │
       │◄──────────────────────────────────│
       │                                   │
```

---

## Part 4: Cross-Network Architecture

### The WireGuard Mesh Approach

Instead of relying on Tailscale (which uses WireGuard under the hood), we can build our own mesh:

```
┌─────────────────────────────────────────────────────────────────┐
│                    WireGuard Mesh Network                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│     Home Network              Mobile               Cloud/VPS    │
│    ┌───────────┐           ┌─────────┐          ┌───────────┐  │
│    │ Obsidian  │◄─────────►│  Phone  │◄────────►│  Hub DNS  │  │
│    │ 10.0.0.1  │           │ 10.0.0.5│          │ 10.0.0.10 │  │
│    └─────┬─────┘           └─────────┘          └─────┬─────┘  │
│          │                                            │        │
│          │              WireGuard tunnels             │        │
│          │                                            │        │
│    ┌─────┴─────┐                               ┌─────┴─────┐  │
│    │neon-laptop│◄─────────────────────────────►│ CoreDNS   │  │
│    │ 10.0.0.2  │                               │ (global)  │  │
│    └───────────┘                               └───────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### NixOS WireGuard Mesh Configuration

```nix
# modules/wireguard-mesh.nix
{ config, lib, pkgs, ... }:

let
  peers = {
    obsidian = {
      publicKey = "...";
      endpoint = "home.example.com:51820";
      allowedIPs = [ "10.0.0.1/32" ];
    };
    neon-laptop = {
      publicKey = "...";
      endpoint = null;  # Dynamic
      allowedIPs = [ "10.0.0.2/32" ];
    };
    hub = {
      publicKey = "...";
      endpoint = "hub.continuum.studio:51820";
      allowedIPs = [ "10.0.0.10/32" ];
      persistentKeepalive = 25;
    };
  };
in {
  networking.wireguard.interfaces.wg-continuum = {
    ips = [ "10.0.0.${config.continuum.nodeId}/24" ];
    listenPort = 51820;
    privateKeyFile = config.sops.secrets.wireguard-key.path;
    
    peers = lib.mapAttrsToList (name: peer: {
      publicKey = peer.publicKey;
      allowedIPs = peer.allowedIPs;
      endpoint = peer.endpoint;
      persistentKeepalive = peer.persistentKeepalive or null;
    }) (lib.filterAttrs (n: _: n != config.networking.hostName) peers);
  };
}
```

### Global DNS Resolution

```
DNS Resolution Flow:

1. Local machine queries: harness.continuum.local
   → Local CoreDNS (if available)
   → Falls back to Hub DNS

2. Hub DNS (cloud VPS) knows all registered services:
   - obsidian.continuum.local → 10.0.0.1 (WireGuard IP)
   - neon-laptop.continuum.local → 10.0.0.2
   
3. WireGuard routes packets to correct node via mesh
```

---

## Part 5: Edge Kubernetes - k0s vs k3s

### Comparison Matrix

| Feature | k0s | k3s |
|---------|-----|-----|
| **Binary Size** | ~180MB | ~70MB |
| **Dependencies** | Zero | Zero |
| **Architecture** | x86_64, ARM64, ARMv7 | x86_64, ARM64, ARMv7 |
| **Datastore** | etcd, SQLite, PostgreSQL, MySQL | etcd, SQLite, PostgreSQL, MySQL |
| **CNI** | Kube-Router (default), Calico | Flannel (default), Calico, Canal |
| **CoreDNS** | ✅ Built-in | ✅ Built-in |
| **Install Method** | Single binary | Single binary |
| **CNCF Status** | Sandbox | Sandbox |
| **Originated By** | Mirantis | Rancher/SUSE |
| **License** | Apache 2.0 | Apache 2.0 |

### Why k0s for Continuum?

1. **Zero dependencies**: Single binary, nothing else needed
2. **Control plane isolation**: Worker nodes don't need kubectl
3. **Flexible datastore**: Can use SQLite for single-node edge
4. **kube-router default**: Better for edge networking
5. **NixOS friendly**: Easy to package and configure declaratively

### k0s on NixOS

```nix
# modules/k0s.nix
{ config, lib, pkgs, ... }:

{
  options.services.k0s = {
    enable = lib.mkEnableOption "k0s Kubernetes";
    role = lib.mkOption {
      type = lib.types.enum [ "controller" "worker" "controller+worker" ];
      default = "controller+worker";
    };
  };

  config = lib.mkIf config.services.k0s.enable {
    environment.systemPackages = [ pkgs.k0s ];
    
    systemd.services.k0s = {
      description = "k0s - Zero Friction Kubernetes";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" ];
      
      serviceConfig = {
        ExecStart = "${pkgs.k0s}/bin/k0s ${config.services.k0s.role}";
        Restart = "always";
        RestartSec = 5;
      };
    };
    
    # CoreDNS configured automatically by k0s
    # But we can customize via k0s.yaml
    environment.etc."k0s/k0s.yaml".text = ''
      apiVersion: k0s.k0sproject.io/v1beta1
      kind: ClusterConfig
      spec:
        network:
          provider: custom  # Use our own CoreDNS
        extensions:
          helm:
            repositories:
              - name: continuum
                url: https://charts.continuum.studio
    '';
  };
}
```

### k3s Alternative

```nix
# If k3s is preferred
{ config, pkgs, ... }:

{
  services.k3s = {
    enable = true;
    role = "server";
    extraFlags = toString [
      "--disable traefik"           # We'll use our own ingress
      "--disable servicelb"         # Not needed for edge
      "--flannel-backend=wireguard" # Use WireGuard for CNI encryption
    ];
  };
}
```

---

## Part 6: Synapsix Service Registry

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Synapsix Service Registry                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────────────────────────────────────────────┐    │
│   │                     Registry GenServer                 │    │
│   │                                                        │    │
│   │  • Node registration (BEAM + non-BEAM)                 │    │
│   │  • Heartbeat management                                │    │
│   │  • Health checking                                     │    │
│   │  • DNS record generation                               │    │
│   │  • Cross-node sync (via BEAM distribution)             │    │
│   │                                                        │    │
│   └───────────────────────────────────────────────────────┘    │
│                              │                                  │
│            ┌─────────────────┼─────────────────┐               │
│            │                 │                 │               │
│            ▼                 ▼                 ▼               │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐        │
│   │  ETS Table  │   │ DNS API     │   │ gRPC API    │        │
│   │  (in-memory)│   │ (CoreDNS)   │   │ (external)  │        │
│   └─────────────┘   └─────────────┘   └─────────────┘        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Elixir Implementation

```elixir
defmodule Synapsix.Registry do
  use GenServer
  require Logger

  @heartbeat_interval 30_000  # 30 seconds
  @expiry_threshold 90_000    # 90 seconds (3 missed heartbeats)

  defmodule Service do
    defstruct [
      :id,           # Unique identifier
      :name,         # Human-readable name
      :type,         # "_synapsix._tcp", "_synapsix-harness._tcp", etc.
      :host,         # Hostname or IP
      :port,         # Port number
      :txt,          # TXT record metadata
      :node,         # BEAM node (if applicable)
      :last_seen,    # Last heartbeat timestamp
      :health        # :healthy, :unhealthy, :unknown
    ]
  end

  # ============================================================================
  # Public API
  # ============================================================================

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc "Register a service"
  def register(service_params) do
    GenServer.call(__MODULE__, {:register, service_params})
  end

  @doc "Deregister a service"
  def deregister(service_id) do
    GenServer.call(__MODULE__, {:deregister, service_id})
  end

  @doc "Heartbeat from a service"
  def heartbeat(service_id) do
    GenServer.cast(__MODULE__, {:heartbeat, service_id})
  end

  @doc "Query services by type (for DNS-SD PTR queries)"
  def list_by_type(service_type) do
    GenServer.call(__MODULE__, {:list_by_type, service_type})
  end

  @doc "Get service details (for DNS-SD SRV/TXT queries)"
  def get_service(service_id) do
    GenServer.call(__MODULE__, {:get_service, service_id})
  end

  @doc "Get all DNS records (for CoreDNS plugin)"
  def get_dns_records do
    GenServer.call(__MODULE__, :get_dns_records)
  end

  # ============================================================================
  # GenServer Callbacks
  # ============================================================================

  def init(_opts) do
    # Create ETS table for fast lookups
    :ets.new(:synapsix_services, [:named_table, :set, :public, read_concurrency: true])
    
    # Schedule expiry check
    schedule_expiry_check()
    
    # Sync with other nodes
    sync_with_cluster()
    
    {:ok, %{}}
  end

  def handle_call({:register, params}, _from, state) do
    service = %Service{
      id: params[:id] || UUID.uuid4(),
      name: params[:name],
      type: params[:type],
      host: params[:host],
      port: params[:port],
      txt: params[:txt] || %{},
      node: params[:node],
      last_seen: System.system_time(:millisecond),
      health: :healthy
    }
    
    :ets.insert(:synapsix_services, {service.id, service})
    
    # Broadcast to cluster
    broadcast_update({:service_registered, service})
    
    Logger.info("Registered service: #{service.name} (#{service.type})")
    {:reply, {:ok, service.id}, state}
  end

  def handle_call({:list_by_type, type}, _from, state) do
    services = :ets.select(:synapsix_services, [
      {{:_, %{type: :"$1", health: :healthy} = :"$2"}, [{:==, :"$1", type}], [:"$2"]}
    ])
    {:reply, services, state}
  end

  def handle_call(:get_dns_records, _from, state) do
    records = :ets.tab2list(:synapsix_services)
    |> Enum.filter(fn {_, svc} -> svc.health == :healthy end)
    |> Enum.flat_map(&service_to_dns_records/1)
    
    {:reply, records, state}
  end

  def handle_cast({:heartbeat, service_id}, state) do
    case :ets.lookup(:synapsix_services, service_id) do
      [{^service_id, service}] ->
        updated = %{service | last_seen: System.system_time(:millisecond), health: :healthy}
        :ets.insert(:synapsix_services, {service_id, updated})
      [] ->
        Logger.warning("Heartbeat for unknown service: #{service_id}")
    end
    {:noreply, state}
  end

  def handle_info(:check_expiry, state) do
    now = System.system_time(:millisecond)
    
    :ets.tab2list(:synapsix_services)
    |> Enum.each(fn {id, service} ->
      age = now - service.last_seen
      cond do
        age > @expiry_threshold ->
          Logger.warning("Service expired: #{service.name}")
          :ets.delete(:synapsix_services, id)
          broadcast_update({:service_expired, id})
        age > @heartbeat_interval * 2 ->
          :ets.insert(:synapsix_services, {id, %{service | health: :unhealthy}})
        true ->
          :ok
      end
    end)
    
    schedule_expiry_check()
    {:noreply, state}
  end

  # ============================================================================
  # Private Functions
  # ============================================================================

  defp schedule_expiry_check do
    Process.send_after(self(), :check_expiry, @heartbeat_interval)
  end

  defp sync_with_cluster do
    Node.list()
    |> Enum.each(fn node ->
      try do
        services = :rpc.call(node, __MODULE__, :get_dns_records, [], 5000)
        Logger.info("Synced #{length(services)} records from #{node}")
      rescue
        _ -> :ok
      end
    end)
  end

  defp broadcast_update(msg) do
    Node.list()
    |> Enum.each(fn node ->
      GenServer.cast({__MODULE__, node}, {:sync, msg})
    end)
  end

  defp service_to_dns_records({_id, service}) do
    domain = "continuum.local"
    instance_name = String.replace(service.name, " ", "\\032")
    
    [
      # PTR record (for service enumeration)
      %{
        type: :ptr,
        name: "#{service.type}.#{domain}",
        data: "#{instance_name}.#{service.type}.#{domain}"
      },
      # SRV record (for resolution)
      %{
        type: :srv,
        name: "#{instance_name}.#{service.type}.#{domain}",
        priority: 0,
        weight: 0,
        port: service.port,
        target: "#{service.host}.#{domain}"
      },
      # TXT record (for metadata)
      %{
        type: :txt,
        name: "#{instance_name}.#{service.type}.#{domain}",
        data: Enum.map(service.txt, fn {k, v} -> "#{k}=#{v}" end)
      }
    ]
  end
end
```

### HTTP API for CoreDNS Plugin

```elixir
defmodule Synapsix.Registry.Router do
  use Plug.Router
  
  plug :match
  plug :dispatch

  # CoreDNS queries this endpoint
  get "/api/dns/records" do
    records = Synapsix.Registry.get_dns_records()
    send_resp(conn, 200, Jason.encode!(records))
  end

  # List services by type
  get "/api/dns/services/:type" do
    services = Synapsix.Registry.list_by_type(type)
    send_resp(conn, 200, Jason.encode!(services))
  end

  # Service registration (for non-BEAM clients)
  post "/api/dns/register" do
    {:ok, body, conn} = read_body(conn)
    params = Jason.decode!(body, keys: :atoms)
    
    case Synapsix.Registry.register(params) do
      {:ok, id} -> send_resp(conn, 201, Jason.encode!(%{id: id}))
      {:error, reason} -> send_resp(conn, 400, Jason.encode!(%{error: reason}))
    end
  end

  # Heartbeat endpoint
  post "/api/dns/heartbeat/:id" do
    Synapsix.Registry.heartbeat(id)
    send_resp(conn, 200, "ok")
  end
end
```

---

## Part 7: Integration Architecture

### Full Stack Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Continuum Studio Architecture                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Phone/Remote                    Home Network                  Cloud    │
│  ┌─────────────┐               ┌─────────────────────────────────────┐ │
│  │ Studio App  │               │                                     │ │
│  │ (Mobile)    │               │  ┌─────────────┐ ┌─────────────┐   │ │
│  │             │◄──WireGuard──►│  │ Obsidian    │ │ neon-laptop │   │ │
│  └─────────────┘               │  │             │ │             │   │ │
│         │                      │  │ • Synapsix  │ │ • Synapsix  │   │ │
│         │                      │  │ • CoreDNS   │ │ • Harnesses │   │ │
│         │                      │  │ • k0s       │ │ • k0s worker│   │ │
│         │                      │  └─────────────┘ └─────────────┘   │ │
│         │                      │         │               │          │ │
│         │                      │         └───────┬───────┘          │ │
│         │                      │                 │                  │ │
│         │                      │      ┌──────────┴──────────┐      │ │
│         │                      │      │ Local DNS (CoreDNS) │      │ │
│         │                      │      │ + Service Registry  │      │ │
│         │                      │      └─────────────────────┘      │ │
│         │                      │                                     │ │
│         │                      └─────────────────────────────────────┘ │
│         │                                        │                     │
│         │                                        │                     │
│         ▼                                        ▼                     │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │                        Hub DNS (VPS)                             │  │
│  │                                                                  │  │
│  │  • Global CoreDNS (public endpoint)                              │  │
│  │  • WireGuard mesh coordinator                                    │  │
│  │  • Registry replication                                          │  │
│  │  • TLS termination (DoH/DoT)                                     │  │
│  │                                                                  │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### DNS Query Resolution

```
Query: cursor-harness.obsidian._synapsix-harness._tcp.continuum.local

1. Mobile Studio App wants to control Cursor harness on Obsidian
2. Query goes to local DNS (phone)
3. Local DNS forwards to Hub DNS (via WireGuard)
4. Hub DNS has record from Obsidian's registry replication
5. Returns: SRV 0 0 4369 obsidian.wg.continuum.local
6. WireGuard routes packet to 10.0.0.1 (Obsidian's WG IP)
7. Connection established!
```

---

## Part 8: Implementation Roadmap

### Phase 1: Foundation (Weeks 1-2)

| Task | Priority | Effort |
|------|----------|--------|
| Set up CoreDNS on Obsidian | HIGH | 1 day |
| Create Synapsix.Registry module | HIGH | 2 days |
| DNS-SD PTR/SRV/TXT generation | HIGH | 1 day |
| HTTP API for CoreDNS | MEDIUM | 1 day |
| NixOS module for CoreDNS | MEDIUM | 1 day |

### Phase 2: Cross-Network (Weeks 3-4)

| Task | Priority | Effort |
|------|----------|--------|
| WireGuard mesh configuration | HIGH | 2 days |
| Hub DNS setup (VPS) | HIGH | 1 day |
| Registry replication | MEDIUM | 2 days |
| DoT/DoH support | MEDIUM | 1 day |

### Phase 3: Edge Kubernetes (Weeks 5-6)

| Task | Priority | Effort |
|------|----------|--------|
| k0s NixOS module | MEDIUM | 2 days |
| Custom CoreDNS plugin | MEDIUM | 3 days |
| Helm charts for Synapsix | LOW | 2 days |
| Multi-cluster service mesh | LOW | 3 days |

---

## Part 9: Comparison with Alternatives

### vs. Tailscale/Headscale

| Aspect | Custom Solution | Tailscale |
|--------|-----------------|-----------|
| **Control** | Full | Limited |
| **DNS Customization** | Full DNS-SD | Magic DNS only |
| **Cost** | Self-hosted | Free tier limited |
| **Vendor Lock-in** | None | Tailscale dependency |
| **Edge Integration** | Native | Additional setup |
| **Complexity** | Higher initial | Lower |

### vs. Consul

| Aspect | Custom Solution | Consul |
|--------|-----------------|--------|
| **Cost** | Free | Enterprise licensing |
| **Complexity** | Lower | Higher |
| **BEAM Integration** | Native | Via HTTP API |
| **Resource Usage** | Minimal | Agent per node |
| **Features** | Service discovery | Full service mesh |

### vs. Kubernetes DNS

| Aspect | Custom Solution | K8s DNS |
|--------|-----------------|---------|
| **Scope** | Global | Cluster-only |
| **Cross-cluster** | Native | Federation needed |
| **Non-K8s services** | Supported | External-dns addon |
| **BEAM awareness** | Native | None |

---

## Part 10: Open Questions

1. **Hub DNS Location**:
   - Self-hosted VPS?
   - Oracle Cloud free tier?
   - Fly.io edge?

2. **Certificate Management**:
   - ACME/Let's Encrypt for DoT/DoH?
   - Self-signed with SPIRE?

3. **Fallback Strategy**:
   - What happens if Hub DNS is unreachable?
   - Local cache TTL strategy?

4. **IPv6**:
   - Support dual-stack?
   - WireGuard IPv6 overlay?

5. **Mobile Integration**:
   - WireGuard client on Android/iOS?
   - Alternative for non-WG clients?

---

## Summary

| Component | Recommendation | Rationale |
|-----------|---------------|-----------|
| **DNS Server** | CoreDNS | Plugin-based, Go, CNCF, k8s native |
| **Service Discovery** | DNS-SD (RFC 6763) | Standard, cross-platform, proven |
| **Service Registry** | Custom Elixir | BEAM-native, integrates with Synapsix |
| **Cross-Network** | WireGuard mesh | Self-hosted, no vendor, encrypted |
| **Edge Kubernetes** | k0s | Zero deps, single binary, flexible |
| **Global DNS** | Hub on VPS | Reachable from anywhere |

**Bottom Line**: Build a self-hosted, vendor-agnostic stack using CoreDNS + DNS-SD + WireGuard + k0s. This gives us complete control, no licensing fees, and seamless integration with Synapsix's BEAM-native architecture.

---

*Document compiled from browser research on CoreDNS documentation, k0s/k3s project sites, RFC 6763 (DNS-SD), and architectural analysis.*
