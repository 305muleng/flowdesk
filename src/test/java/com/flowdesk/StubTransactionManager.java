package com.flowdesk;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

// 仅驱动 Spring 事务同步和事件生命周期；不代表真实数据库提交/回滚。
final class StubTransactionManager extends AbstractPlatformTransactionManager {

    private final ThreadLocal<Resource> current = new ThreadLocal<>();
    int commits;
    int rollbacks;
    int suspensions;

    private static final class Tx implements SmartTransactionObject {

        Resource resource;

        @Override
        public boolean isRollbackOnly() {
            return resource != null && resource.rollbackOnly;
        }

        @Override
        public void flush() {}
    }

    private static final class Resource {

        boolean active;
        boolean rollbackOnly;
    }

    @Override
    protected Object doGetTransaction() {
        Tx tx = new Tx();
        tx.resource = current.get();
        return tx;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        Resource resource = ((Tx) transaction).resource;
        return resource != null && resource.active;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Tx tx = (Tx) transaction;
        tx.resource = new Resource();
        tx.resource.active = true;
        current.set(tx.resource);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks++;
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        ((Tx) status.getTransaction()).resource.rollbackOnly = true;
    }

    @Override
    protected Object doSuspend(Object transaction) {
        suspensions++;
        Resource resource = current.get();
        current.remove();
        ((Tx) transaction).resource = null;
        return resource;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        ((Tx) transaction).resource = (Resource) suspendedResources;
        current.set((Resource) suspendedResources);
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        ((Tx) transaction).resource.active = false;
        current.remove();
    }
}
