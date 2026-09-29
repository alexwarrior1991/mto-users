package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * What runs with {@code app.rabbitmq.enabled=false}: the tests, and an environment without a
 * broker. The audit line still comes out; only the event is not published, and it says so once.
 */
public class NoOpUsersEventPublisher implements UsersEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpUsersEventPublisher.class);

    public NoOpUsersEventPublisher() {
        log.info("Users events are not published: app.rabbitmq.enabled is false");
    }

    @Override
    public void publish(AdminAction action, String targetUserId, String targetUsername, Map<String, Object> detail) {
        log.debug("Users event not published (messaging disabled): action={} targetUserId={}", action, targetUserId);
    }
}
