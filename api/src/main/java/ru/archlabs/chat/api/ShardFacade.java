package ru.archlabs.chat.api;

/**
 * Facade that exposes the deterministic entity-to-shard assignment.
 */
public final class ShardFacade {
    private final ChatGateway gateway;

    ShardFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public ShardAssignment find(String user) {
        return gateway.shardAssignment(FacadeSupport.requireUser(user));
    }
}
