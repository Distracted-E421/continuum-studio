# Service Discovery Research: Synapsix + Continuum Studio

> **Research Date**: January 2026  
> **Purpose**: Evaluate service discovery mechanisms for multi-machine harness orchestration  
> **Scope**: mDNS/Avahi, Consul, BEAM-native, and custom approaches

---

## Executive Summary

| Solution | Complexity | Scale | Infrastructure | Cross-Platform |
|----------|-----------|-------|----------------|----------------|
| **mDNS/Avahi** | Low | LAN only | None | Linux/macOS/Windows |
| **Consul** | High | Global | Consul cluster | All |
| **BEAM epmd** | Low | Per-network | epmd daemon | BEAM only |
| **Tailscale** | Medium | Global | Tailscale | All |
| **Custom DNS-SD** | Medium | Configurable | DNS server | All |

**Recommendation for Synapsix**:

- **Primary**: mDNS (Avahi) for local network discovery
- **Secondary**: Tailscale MagicDNS for cross-network
- **Future**: Consul for enterprise/k8s deployments

---

## Part 1: Multicast DNS (mDNS) / Avahi

### Overview

mDNS is a zero-configuration protocol that resolves hostnames to IP addresses without a central DNS server. It's the foundation of Apple's Bonjour and Linux's Avahi.

### How It Works

```
┌─────────────┐          ┌─────────────┐
│   Client    │          │   Service   │
│  (Studio)   │          │ (Synapsix)  │
└──────┬──────┘          └──────┬──────┘
       │                        │
       │  mDNS Query (multicast)│
       │  "synapsix.local?"     │
       ├───────────────────────►│
       │                        │
       │  mDNS Response         │
       │  "synapsix.local = IP" │
       │◄───────────────────────┤
       │                        │
```

**Key Details:**

- **Address**: 224.0.0.251 (IPv4) or ff02::fb (IPv6)
- **Port**: UDP 5353
- **Domain**: `.local` suffix by convention
- **Implementation**: Avahi (Linux), Bonjour (macOS), built-in (Windows 10+)

### DNS Service Discovery (DNS-SD)

DNS-SD extends mDNS to discover services, not just hostnames:

```
Service Registration:
  _synapsix._tcp.local.  PTR  obsidian._synapsix._tcp.local.
  obsidian._synapsix._tcp.local.  SRV  0 0 4369 obsidian.local.
  obsidian._synapsix._tcp.local.  TXT  "version=1.0" "harnesses=cursor,android-studio"
```

**Service Types for Synapsix:**

- `_synapsix._tcp` - Main Synapsix service
- `_synapsix-harness._tcp` - Individual harness
- `_continuum-studio._tcp` - Studio UI

### NixOS/Avahi Configuration

```nix
# In NixOS configuration
services.avahi = {
  enable = true;
  nssmdns4 = true;  # Enable .local resolution
  publish = {
    enable = true;
    domain = true;
    addresses = true;
    userServices = true;
  };
};

# Publish Synapsix service
environment.etc."avahi/services/synapsix.service".text = ''
  <?xml version="1.0" standalone='no'?>
  <!DOCTYPE service-group SYSTEM "avahi-service.dtd">
  <service-group>
    <name replace-wildcards="yes">Synapsix on %h</name>
    <service>
      <type>_synapsix._tcp</type>
      <port>4369</port>
      <txt-record>version=1.0</txt-record>
      <txt-record>harnesses=cursor</txt-record>
    </service>
  </service-group>
'';
```

### Elixir mDNS Integration

```elixir
# Using mdns_lite library
defmodule Synapsix.Discovery do
  use GenServer
  require Logger

  @service_type "_synapsix._tcp"
  
  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  def init(_) do
    # Register our service
    MdnsLite.publish_service(%{
      name: node_name(),
      type: @service_type,
      port: epmd_port(),
      txt: [
        "version=#{Application.spec(:synapsix, :vsn)}",
        "harnesses=#{list_harnesses()}"
      ]
    })
    
    # Start discovery
    MdnsLite.subscribe(@service_type)
    {:ok, %{known_nodes: %{}}}
  end

  def handle_info({:mdns, :discovered, service}, state) do
    Logger.info("Discovered Synapsix node: #{service.name}")
    # Attempt BEAM connection
    spawn(fn -> connect_to_node(service) end)
    {:noreply, put_in(state.known_nodes[service.name], service)}
  end

  defp connect_to_node(service) do
    node_atom = String.to_atom("synapsix@#{service.host}")
    Node.connect(node_atom)
  end
end
```

### mDNS Wins

1. **Zero infrastructure**: No central server needed
2. **Automatic**: Services appear/disappear automatically
3. **Standard**: RFC 6762 (mDNS) + RFC 6763 (DNS-SD)
4. **Cross-platform**: Works on Linux, macOS, Windows
5. **Fast**: Sub-second discovery on LAN
6. **NixOS native**: Avahi in nixpkgs, easy to configure

### mDNS Flaws

1. **LAN only**: Doesn't cross network boundaries
2. **No security**: Anyone on LAN can see/spoof services
3. **`.local` conflicts**: Can conflict with actual DNS zones
4. **Firewall issues**: Multicast must be allowed
5. **IPv6 complexity**: Dual-stack can be tricky
6. **Scale limits**: Designed for small networks (<100 services)

---

## Part 2: Consul

### Overview

Consul is HashiCorp's service mesh and service discovery solution. It provides:

- Service registration and discovery
- Health checking
- Key/value storage
- Multi-datacenter support

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Consul Cluster                        │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                 │
│  │ Server  │  │ Server  │  │ Server  │  (Raft consensus)│
│  └────┬────┘  └────┬────┘  └────┬────┘                 │
│       └───────────────┬────────────┘                    │
│                       │                                 │
└───────────────────────┼─────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
   ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
   │ Agent   │    │ Agent   │    │ Agent   │
   │(Obsidian)│   │(neon)   │    │(pi)     │
   └────┬────┘    └────┬────┘    └────┬────┘
        │              │              │
   ┌────┴────┐    ┌────┴────┐    ┌────┴────┐
   │Synapsix │    │Synapsix │    │Synapsix │
   │ Studio  │    │ Harness │    │ Harness │
   └─────────┘    └─────────┘    └─────────┘
```

### Service Registration

```hcl
# synapsix.hcl
service {
  name = "synapsix"
  id   = "synapsix-obsidian"
  port = 4369
  
  tags = ["elixir", "harness-manager"]
  
  meta = {
    version  = "1.0.0"
    harnesses = "cursor,android-studio"
  }
  
  check {
    name     = "EPMD Health"
    tcp      = "127.0.0.1:4369"
    interval = "10s"
    timeout  = "1s"
  }
  
  check {
    name     = "Harness Status"
    http     = "http://127.0.0.1:4000/health"
    interval = "30s"
  }
}
```

### Service Discovery Query

```bash
# DNS interface
dig @127.0.0.1 -p 8600 synapsix.service.consul SRV

# HTTP API
curl http://localhost:8500/v1/catalog/service/synapsix

# Returns:
# [
#   {
#     "Node": "obsidian",
#     "Address": "192.168.1.10",
#     "ServicePort": 4369,
#     "ServiceMeta": {"harnesses": "cursor,android-studio"}
#   },
#   {
#     "Node": "neon-laptop",
#     "Address": "192.168.1.11",
#     "ServicePort": 4369,
#     "ServiceMeta": {"harnesses": "godot"}
#   }
# ]
```

### Elixir Consul Integration

```elixir
# Using consul_ex library
defmodule Synapsix.ConsulDiscovery do
  use GenServer
  require Logger

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  def init(_) do
    # Register service
    :ok = Consul.Agent.service_register(%{
      id: "synapsix-#{node_name()}",
      name: "synapsix",
      port: 4369,
      meta: %{
        harnesses: list_harnesses() |> Enum.join(","),
        version: "1.0.0"
      },
      check: %{
        tcp: "127.0.0.1:4369",
        interval: "10s"
      }
    })
    
    # Start polling for other nodes
    schedule_discovery()
    {:ok, %{}}
  end

  def handle_info(:discover, state) do
    case Consul.Catalog.service("synapsix") do
      {:ok, services} ->
        Enum.each(services, fn svc ->
          connect_to_synapsix(svc)
        end)
      {:error, reason} ->
        Logger.warning("Discovery failed: #{inspect(reason)}")
    end
    
    schedule_discovery()
    {:noreply, state}
  end

  defp schedule_discovery do
    Process.send_after(self(), :discover, 30_000)
  end
end
```

### Consul Wins

1. **Enterprise-grade**: Battle-tested at scale
2. **Multi-datacenter**: Built-in WAN federation
3. **Health checking**: Services removed when unhealthy
4. **Key/Value**: Can store configuration
5. **DNS interface**: Standard DNS queries work
6. **Service mesh**: mTLS, traffic policies
7. **Kubernetes**: Native K8s integration

### Consul Flaws

1. **Infrastructure overhead**: Need 3+ servers for HA
2. **Complexity**: Significant learning curve
3. **Resource usage**: Agents on every node
4. **Overkill for homelab**: Designed for larger deployments
5. **Configuration**: Lots of knobs to tune
6. **Licensing**: Enterprise features require license

---

## Part 3: BEAM Native (epmd + Manual)

### Overview

BEAM has its own service discovery via `epmd` (Erlang Port Mapper Daemon) and manual node connection.

### How epmd Works

```
┌─────────────────┐
│     epmd        │  (Port 4369)
│   (per host)    │
├─────────────────┤
│ studio@obs → 45123 │
│ synapsix@obs → 45456│
└─────────────────┘
```

Each BEAM node registers with local epmd, which tracks port mappings.

### Manual Discovery Pattern

```elixir
defmodule Synapsix.BEAMDiscovery do
  @known_hosts [
    "obsidian",
    "neon-laptop", 
    "framework",
    "pi-server"
  ]
  
  def discover_and_connect do
    Enum.each(@known_hosts, fn host ->
      node = :"synapsix@#{host}"
      case Node.connect(node) do
        true -> 
          Logger.info("Connected to #{node}")
        false -> 
          Logger.debug("#{node} not available")
        :ignored ->
          :ok  # Already connected
      end
    end)
  end
  
  # Or use DNS SRV records
  def discover_via_dns do
    case :inet_res.getbyname('_synapsix._tcp.homelab.local', :srv) do
      {:ok, {:hostent, _, _, :srv, _, records}} ->
        Enum.map(records, fn {_, _, port, host} ->
          {to_string(host), port}
        end)
      {:error, _} ->
        []
    end
  end
end
```

### epmd Wins

1. **Built-in**: No extra infrastructure
2. **Simple**: Just works for BEAM nodes
3. **Fast**: Direct TCP connection
4. **No overhead**: Single daemon per host

### epmd Flaws

1. **BEAM only**: Doesn't help non-BEAM services
2. **No automatic discovery**: Must know hostnames
3. **Security**: No authentication (relies on cookie)
4. **Port conflicts**: epmd must be on 4369
5. **No health checking**: Dead nodes stay registered until restart

---

## Part 4: Tailscale MagicDNS

### Overview

Tailscale provides automatic DNS for all devices in your tailnet. Every device gets a `*.ts.net` hostname.

### How It Works

```
Tailscale Network (100.x.x.x):

obsidian.darter-fujita.ts.net     → 100.125.197.80
neon-laptop.darter-fujita.ts.net  → 100.125.197.86
framework.darter-fujita.ts.net    → 100.64.0.3
```

### Integration with Synapsix

```elixir
defmodule Synapsix.TailscaleDiscovery do
  @tailnet_suffix ".darter-fujita.ts.net"
  
  @known_machines [
    "obsidian",
    "neon-laptop",
    "framework"
  ]
  
  def discover_nodes do
    @known_machines
    |> Enum.map(&"#{&1}#{@tailnet_suffix}")
    |> Enum.filter(&reachable?/1)
    |> Enum.map(&connect_synapsix/1)
  end
  
  defp reachable?(host) do
    case :inet.gethostbyname(String.to_charlist(host)) do
      {:ok, _} -> true
      _ -> false
    end
  end
  
  defp connect_synapsix(host) do
    node = String.to_atom("synapsix@#{host}")
    Node.connect(node)
  end
end
```

### Tailscale Wins

1. **Already deployed**: You have Tailscale running
2. **Cross-network**: Works anywhere with internet
3. **Automatic DNS**: No configuration needed
4. **Secure**: All traffic encrypted via WireGuard
5. **MagicDNS**: Stable hostnames even if IPs change

### Tailscale Flaws

1. **External dependency**: Requires Tailscale running
2. **Internet required**: Won't work offline
3. **Static naming**: Must know machine names
4. **No service discovery**: Just hostnames, not services

---

## Part 5: Hybrid Approach (Recommended)

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Discovery Layer                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐   │
│   │    mDNS       │   │   Tailscale   │   │   Configured  │   │
│   │   (Local)     │   │   (Remote)    │   │    (Manual)   │   │
│   └───────┬───────┘   └───────┬───────┘   └───────┬───────┘   │
│           │                   │                   │            │
│           └───────────────────┼───────────────────┘            │
│                               │                                 │
│                     ┌─────────┴─────────┐                      │
│                     │ Discovery Manager │                      │
│                     │   (GenServer)     │                      │
│                     └─────────┬─────────┘                      │
│                               │                                 │
└───────────────────────────────┼─────────────────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
            ┌───────┴───────┐       ┌───────┴───────┐
            │  BEAM Cluster │       │ Service List  │
            │  (Synapsix)   │       │ (Non-BEAM)    │
            └───────────────┘       └───────────────┘
```

### Implementation

```elixir
defmodule Synapsix.Discovery.Manager do
  use GenServer
  require Logger

  @discovery_interval 30_000  # 30 seconds
  @service_type "_synapsix._tcp"

  defmodule State do
    defstruct [
      :mdns_enabled,
      :tailscale_suffix,
      :static_nodes,
      :discovered_nodes
    ]
  end

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  def init(opts) do
    state = %State{
      mdns_enabled: Keyword.get(opts, :mdns, true),
      tailscale_suffix: Keyword.get(opts, :tailscale_suffix),
      static_nodes: Keyword.get(opts, :static_nodes, []),
      discovered_nodes: MapSet.new()
    }
    
    # Start discovery sources
    if state.mdns_enabled, do: start_mdns()
    
    schedule_discovery()
    {:ok, state}
  end

  # Called periodically
  def handle_info(:discover, state) do
    # 1. mDNS discovery (local network)
    mdns_nodes = if state.mdns_enabled, do: mdns_discover(), else: []
    
    # 2. Tailscale nodes (if configured)
    tailscale_nodes = if state.tailscale_suffix do
      tailscale_discover(state.tailscale_suffix)
    else
      []
    end
    
    # 3. Static nodes (always try)
    static_nodes = state.static_nodes
    
    # Merge and deduplicate
    all_nodes = (mdns_nodes ++ tailscale_nodes ++ static_nodes)
                |> Enum.uniq()
    
    # Connect to new nodes
    new_nodes = Enum.reject(all_nodes, &MapSet.member?(state.discovered_nodes, &1))
    
    Enum.each(new_nodes, fn node ->
      case Node.connect(node) do
        true -> 
          Logger.info("Connected to Synapsix node: #{node}")
        false -> 
          Logger.debug("Could not connect to #{node}")
        :ignored -> 
          :ok
      end
    end)
    
    # Update state
    connected = Node.list() |> Enum.filter(&synapsix_node?/1)
    
    schedule_discovery()
    {:noreply, %{state | discovered_nodes: MapSet.new(connected)}}
  end

  # mDNS discovery via Avahi/mdns_lite
  defp mdns_discover do
    case MdnsLite.list_services(@service_type) do
      {:ok, services} ->
        Enum.map(services, fn svc ->
          String.to_atom("synapsix@#{svc.host}")
        end)
      _ ->
        []
    end
  end

  # Tailscale discovery via MagicDNS
  defp tailscale_discover(suffix) do
    known_hosts = ["obsidian", "neon-laptop", "framework", "pi-server"]
    
    Enum.filter(known_hosts, fn host ->
      fqdn = "#{host}#{suffix}"
      case :inet.gethostbyname(String.to_charlist(fqdn)) do
        {:ok, _} -> true
        _ -> false
      end
    end)
    |> Enum.map(&String.to_atom("synapsix@#{&1}#{suffix}"))
  end

  defp synapsix_node?(node) do
    node_str = Atom.to_string(node)
    String.starts_with?(node_str, "synapsix@")
  end

  defp schedule_discovery do
    Process.send_after(self(), :discover, @discovery_interval)
  end
end
```

### Configuration

```elixir
# config/config.exs
config :synapsix, Synapsix.Discovery.Manager,
  mdns: true,
  tailscale_suffix: ".darter-fujita.ts.net",
  static_nodes: [
    :"synapsix@pi-server.local"  # Always try pi-server
  ]
```

### NixOS Module

```nix
# synapsix/nixos-module.nix
{ config, lib, pkgs, ... }:

with lib;

let
  cfg = config.services.synapsix;
in {
  options.services.synapsix = {
    enable = mkEnableOption "Synapsix AI harness orchestrator";
    
    discovery = {
      mdns.enable = mkOption {
        type = types.bool;
        default = true;
        description = "Enable mDNS service discovery";
      };
      
      tailscaleSuffix = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "Tailscale network suffix for MagicDNS";
      };
      
      staticNodes = mkOption {
        type = types.listOf types.str;
        default = [];
        description = "Static list of Synapsix nodes to connect to";
      };
    };
  };

  config = mkIf cfg.enable {
    # Enable Avahi for mDNS
    services.avahi = mkIf cfg.discovery.mdns.enable {
      enable = true;
      nssmdns4 = true;
      publish = {
        enable = true;
        addresses = true;
        userServices = true;
      };
    };

    # Publish Synapsix service via Avahi
    environment.etc."avahi/services/synapsix.service" = mkIf cfg.discovery.mdns.enable {
      text = ''
        <?xml version="1.0" standalone='no'?>
        <!DOCTYPE service-group SYSTEM "avahi-service.dtd">
        <service-group>
          <name replace-wildcards="yes">Synapsix on %h</name>
          <service>
            <type>_synapsix._tcp</type>
            <port>4369</port>
          </service>
        </service-group>
      '';
    };

    # Firewall for mDNS
    networking.firewall.allowedUDPPorts = mkIf cfg.discovery.mdns.enable [ 5353 ];
  };
}
```

---

## Part 6: Comparison Matrix

### Feature Comparison

| Feature | mDNS | Consul | BEAM/epmd | Tailscale | Hybrid |
|---------|------|--------|-----------|-----------|--------|
| Auto-discovery | ✅ | ✅ | ❌ | ❌ | ✅ |
| Cross-network | ❌ | ✅ | ❌ | ✅ | ✅ |
| Health checks | ❌ | ✅ | ❌ | ❌ | ⚠️ |
| Zero config | ✅ | ❌ | ✅ | ✅ | ⚠️ |
| Service metadata | ✅ | ✅ | ❌ | ❌ | ✅ |
| Infrastructure | None | Cluster | epmd | Tailscale | Avahi |
| Non-BEAM support | ✅ | ✅ | ❌ | ✅ | ✅ |

### Use Case Matrix

| Scenario | Recommended |
|----------|-------------|
| Single LAN, homelab | mDNS (Avahi) |
| Multi-location, Tailscale VPN | Tailscale MagicDNS |
| Enterprise, Kubernetes | Consul |
| Simple, known hosts | Static configuration |
| Mixed environment | Hybrid approach |

---

## Part 7: Recommendations for Synapsix

### Immediate (Phase 1)

1. **Enable Avahi** on all NixOS machines with Synapsix
2. **Publish** `_synapsix._tcp` service via DNS-SD
3. **Use mdns_lite** in Elixir for discovery
4. **Fall back** to Tailscale MagicDNS for remote nodes

### Future (Phase 2)

1. **Consider Consul** if:
   - Deploying to Kubernetes
   - Need health-based routing
   - Enterprise customers require it

2. **Custom DNS** if:
   - Have existing DNS infrastructure
   - Need deterministic naming

### Configuration Defaults

```elixir
# Recommended defaults for Synapsix
config :synapsix, Synapsix.Discovery,
  strategies: [
    # 1. Local network via mDNS
    {Synapsix.Discovery.MDNS, service_type: "_synapsix._tcp"},
    
    # 2. Remote via Tailscale (if suffix configured)
    {Synapsix.Discovery.Tailscale, suffix: System.get_env("TAILSCALE_SUFFIX")},
    
    # 3. Static fallbacks
    {Synapsix.Discovery.Static, nodes: []}
  ],
  
  # How often to scan for new nodes
  discovery_interval: 30_000,
  
  # How long to wait before retrying failed connections
  retry_interval: 60_000
```

---

## Appendix: Quick Reference

### mDNS Service Publication (Avahi)

```xml
<!-- /etc/avahi/services/myservice.service -->
<service-group>
  <name>My Service</name>
  <service>
    <type>_http._tcp</type>
    <port>8080</port>
    <txt-record>path=/api</txt-record>
  </service>
</service-group>
```

### Consul Service Registration (CLI)

```bash
consul services register -name=synapsix -port=4369 \
  -meta="version=1.0" -meta="harnesses=cursor"
```

### BEAM Node Connection

```elixir
# Connect to specific node
Node.connect(:"synapsix@obsidian.local")

# List connected nodes
Node.list()

# Ping node
Node.ping(:"synapsix@neon-laptop.local")
```

### Tailscale Status

```bash
# List all devices
tailscale status

# Get device IP
tailscale ip obsidian

# Check MagicDNS
nslookup obsidian.darter-fujita.ts.net
```

---

*Document compiled from browser research on Consul service discovery, mDNS/Avahi documentation, and existing homelab Tailscale configuration.*
