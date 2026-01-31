# On Agent "Connection Loss" Issues

**Date**: January 31, 2026
**Context**: Observation during Synapsix Dialog development session

## The Observation

Agents sometimes "lose connection" during sessions. The user noted:

> "I noticed that agents sometimes 'lose connection', but it should not be an issue on our end, which could mean a few things: worst case, there is a cursor server side trip wire that has certain conditions to be met to throttle/kill the session to cut down their costs, best case it's something we can fix in some way"

## Hypotheses

### Server-Side (Cursor)

1. **Cost Throttling**: Cursor may have server-side tripwires that detect certain patterns:
   - High token consumption rate
   - Long session duration
   - Specific tool usage patterns (many shell commands, large file reads)
   - Automation detection (rapid sequential commands)

2. **Rate Limiting**: API backends may have per-session or per-user limits that disconnect rather than error.

3. **WebSocket Timeouts**: Long periods of tool execution without message flow may trigger connection drops.

### Client-Side (Fixable)

1. **Keepalive Issues**: The editor's connection to the agent backend may lack proper keepalive mechanisms.

2. **Memory Pressure**: Large context windows may cause browser/Electron tab crashes.

3. **Network Interruptions**: Tailscale/VPN reconnections, WiFi transitions.

## Investigation Areas

1. **Logging**: Add instrumentation to track when disconnections occur relative to:
   - Session duration
   - Number of tool calls
   - Token count estimates
   - Type of operations being performed

2. **Pattern Analysis**: Collect data on multiple disconnection events to identify common factors.

3. **Mitigation Strategies**:
   - Periodic "heartbeat" operations to keep connection alive
   - Session state persistence for recovery
   - Graceful degradation (save context before expensive operations)

## Potential Cursor Side Tripwires (Speculative)

If Cursor is implementing cost controls, likely triggers might be:

- Sessions exceeding X hours continuous use
- More than Y tool calls per minute
- Total token consumption approaching limits
- Patterns that look like automated abuse (scripted interactions)
- Background/unattended sessions (no user messages for extended periods)

## Recommended Actions

1. **Monitor and Document**: Track each disconnection with context (time, operations, session state)
2. **Test Boundaries**: Intentionally test edge cases to identify limits
3. **Build Resilience**: Synapsix Dialog's hold pattern is a good start - expand to session recovery

## Related Work

- `synapsix-dialog` holding pattern implementation
- Session persistence/recovery in Continuum Studio
- Agent state serialization for handoff
