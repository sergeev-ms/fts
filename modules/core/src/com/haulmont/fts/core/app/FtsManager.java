/*
 * Copyright (c) 2008-2016 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/commercial-software-license for details.
 */
package com.haulmont.fts.core.app;

import com.google.common.base.Strings;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Query;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.app.FtsSender;
import com.haulmont.cuba.core.app.ServerInfoAPI;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.entity.FtsChangeType;
import com.haulmont.cuba.core.entity.FtsQueue;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.persistence.DbTypeConverter;
import com.haulmont.cuba.security.app.Authenticated;
import com.haulmont.cuba.security.app.Authentication;
import com.haulmont.fts.core.sys.*;
import com.haulmont.fts.global.FtsConfig;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.lucene.index.IndexUpgrader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

@Component(FtsManagerAPI.NAME)
public class FtsManager implements FtsManagerAPI {

    private final Logger log = LoggerFactory.getLogger(FtsManager.class);

    private volatile Map<String, EntityDescr> descrByClassName;
    private volatile Map<String, EntityDescr> descrByName;

    protected final ReentrantLock writeLock = new ReentrantLock();
    protected volatile boolean writing;

    protected final ReentrantLock reindexLock = new ReentrantLock();
    protected volatile boolean reindexing;
    protected volatile Queue<String> reindexEntitiesQueue = new ConcurrentLinkedQueue<>();

    protected volatile Directory directory;

    protected static final int DEL_CHUNK = 10;

    @Inject
    protected FtsConfig config;

    @Inject
    protected FtsConfig coreConfig;

    protected String serverId;

    @Inject
    protected Authentication authentication;

    @Inject
    protected Persistence persistence;

    @Inject
    protected Scripting scripting;

    @Inject
    protected Metadata metadata;

    @Inject
    protected ConfigLoader configLoader;

    @Inject
    protected FtsSender ftsSender;

    @Inject
    public void setServerInfo(ServerInfoAPI serverInfo) {
        serverId = serverInfo.getServerId();
    }

    @Override
    public boolean isEnabled() {
        return config.getEnabled();
    }

    @Authenticated
    @Override
    public void setEnabled(boolean value) {
        config.setEnabled(value);
    }

    @Override
    public boolean isWriting() {
        return writing;
    }

    @Override
    public boolean isReindexing() {
        return reindexing;
    }

    @Override
    public Queue<String> getReindexEntitiesQueue() {
        return reindexEntitiesQueue;
    }

    protected Map<String, EntityDescr> getDescrByClassName() {
        if (descrByClassName == null) {
            synchronized (this) {
                if (descrByClassName == null) {
                    descrByClassName = configLoader.loadConfiguration();
                }
            }
        }
        return descrByClassName;
    }

    @Override
    public Map<String, EntityDescr> getDescrByName() {
        if (descrByName == null) {
            synchronized (this) {
                if (descrByName == null) {
                    descrByName = new HashMap<>(getDescrByClassName().size());
                    for (EntityDescr descr : getDescrByClassName().values()) {
                        String name = descr.getMetaClass().getName();
                        descrByName.put(name, descr);
                    }
                }
            }
        }
        return descrByName;
    }

    @Override
    public List<Entity> getSearchableEntities(Entity entity) {
        List<Entity> list = new ArrayList<>();

        EntityDescr descr = getDescrByClassName().get(entity.getClass().getName());
        if (descr == null) {
            Class originalClass = metadata.getExtendedEntities().getOriginalClass(entity.getMetaClass());
            if (originalClass != null)
                descr = getDescrByClassName().get(originalClass.getName());
            if (descr == null)
                return list;
        }

        Set<String> properties = descr.getPropertyNames();

        Set<String> ownProperties = new HashSet<>(properties.size());
        for (String property : properties) {
            String p = property.indexOf(".") < 0 ? property : property.substring(0, property.indexOf("."));
            ownProperties.add(p);
        }

        Set<String> dirty = persistence.getTools().getDirtyFields(entity);
        for (String s : dirty) {
            if (ownProperties.contains(s)) {
                if (StringUtils.isBlank(descr.getSearchableIfScript())) {
                    list.add(entity);
                } else if (runSearchableIf(entity, descr)) {
                    list.add(entity);
                }
                break;
            }
        }

        if (!StringUtils.isBlank(descr.getSearchablesScript())) {
            Map<String, Object> params = new HashMap<>();
            params.put("entity", entity);
            params.put("searchables", list);
            scripting.evaluateGroovy(descr.getSearchablesScript(), params);
        }

        return list;
    }

    protected boolean runSearchableIf(Entity entity, EntityDescr descr) {
        Map<String, Object> params = new HashMap<>();
        params.put("entity", entity);
        Boolean value = scripting.evaluateGroovy(descr.getSearchableIfScript(), params);
        return BooleanUtils.isTrue(value);
    }

    @Override
    public int processQueue() {
        if (!AppContext.isStarted())
            return 0;

        if (!config.getEnabled())
            return 0;

        if (!reindexEntitiesQueue.isEmpty()) {
            log.debug("Unable to process queue: there are entities that are waiting for reindex");
            return 0;
        }

        log.debug("Start processing queue");
        int count = 0;
        boolean locked = writeLock.tryLock();
        if (!locked) {
            log.warn("Unable to process queue: writing at the moment");
            return count;
        }

        authentication.begin();
        try {
            writing = true;

            List<FtsQueue> list = loadQueuedItems();
            list = new ArrayList<>(list);
            if (!list.isEmpty()) {
                count = initIndexer(list);
                removeQueuedItems(list);
            }
        } finally {
            writeLock.unlock();
            writing = false;
            authentication.end();
        }
        log.debug(count + " queue items successfully processed");
        return count;
    }

    protected List<FtsQueue> loadQueuedItems() {
        List<FtsQueue> list;

        boolean useServerId = !coreConfig.getIndexingHosts().isEmpty();
        int maxSize = coreConfig.getIndexingBatchSize();

        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            String queryString = String.format("select q from sys$FtsQueue q where q.fake = false and %s order by q.createTs",
                    (useServerId ? "q.indexingHost = ?1" : "q.indexingHost is null"));
            Query query = em.createQuery(queryString);
            if (useServerId)
                query.setParameter(1, serverId);
            query.setMaxResults(maxSize);
            list = query.getResultList();
            tx.commit();
        } finally {
            tx.end();
        }
        return list;
    }

    protected void removeQueuedItems(List<FtsQueue> list) {
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();

            for (int i = 0; i < list.size(); i += DEL_CHUNK) {
                StringBuilder sb = new StringBuilder("delete from SYS_FTS_QUEUE where ID in (");
                List<FtsQueue> sublist = list.subList(i, Math.min(i + DEL_CHUNK, list.size()));
                for (int idx = 0; idx < sublist.size(); idx++) {
                    sb.append("?");
                    if (idx < sublist.size() - 1)
                        sb.append(", ");
                }
                sb.append(")");

                DbTypeConverter converter = persistence.getDbTypeConverter();

                Query query = em.createNativeQuery(sb.toString());
                for (int idx = 0; idx < sublist.size(); idx++) {
                    query.setParameter(idx + 1, converter.getSqlObject(sublist.get(idx).getId()));
                }
                query.executeUpdate();
            }

            tx.commit();
        } finally {
            tx.end();
        }
    }

    protected int initIndexer(List<FtsQueue> list) {
        LuceneIndexer indexer = createLuceneIndexer();
        List<FtsQueue> unindexed = new ArrayList<>(list.size());
        int count = 0;
        try {
            for (FtsQueue ftsQueue : list) {
                try {
                    indexer.indexEntity(ftsQueue.getEntityName(), ftsQueue.getEntityId(), ftsQueue.getChangeType());
                    count++;
                } catch (IndexingException e) {
                    if (e.getEntityType() != IndexingException.EntityType.FILE)
                        unindexed.add(ftsQueue);
                }
            }
            if (!unindexed.isEmpty()) {
                list.removeAll(unindexed);
            }
        } finally {
            indexer.close();
        }
        return count;
    }

    protected LuceneIndexer createLuceneIndexer() {
        return new LuceneIndexer(getDescrByName(), getDirectory(), coreConfig.getStoreContentInIndex());
    }

    protected FtsConfig getConfig() {
        return config;
    }

    @Override
    public String optimize() {
        if (!AppContext.isStarted())
            return "Application is not started";

        if (!config.getEnabled())
            return "FTS is disabled";

        log.debug("Start optimize");
        boolean locked = writeLock.tryLock();
        if (!locked) {
            return "Unable to optimize: writing at the moment";
        }

        authentication.begin();
        LuceneWriter luceneWriter = new LuceneWriter(getDirectory());
        try {
            writing = true;
            luceneWriter.optimize();
            return "Done";
        } catch (Throwable e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        } finally {
            luceneWriter.close();
            writeLock.unlock();
            writing = false;
            authentication.end();
        }
    }

    @Override
    public String upgrade() {
        IndexUpgrader upgrader = new IndexUpgrader(getDirectory());
        try {
            upgrader.upgrade();
        } catch (IOException e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        }
        return "successful";
    }

    @Override
    public boolean showInResults(String entityName) {
        EntityDescr descr = getDescrByName().get(entityName);
        return descr != null && descr.isShow();
    }

    @Override
    public void deleteIndexForEntity(String entityName) {
        boolean locked = writeLock.tryLock();
        if (!locked) {
            throw new IllegalStateException("Unable to delete index: writing at the moment");
        }
        LuceneWriter writer = new LuceneWriter(getDirectory());
        try {
            writing = true;
            writer.deleteIndexForEntity(entityName);
        } finally {
            writer.close();
            writeLock.unlock();
            writing = false;
        }
    }

    @Override
    public void deleteIndex() {
        boolean locked = writeLock.tryLock();
        if (!locked) {
            throw new IllegalStateException("Unable to delete index: writing at the moment");
        }
        LuceneWriter writer = new LuceneWriter(getDirectory());
        try {
            writing = true;
            writer.deleteAll();
        } finally {
            writer.close();
            writeLock.unlock();
            writing = false;
        }
    }

    @Override
    public int reindexEntity(String entityName) {
        int count = 0;

        MetaClass metaClass = metadata.getSession().getClass(entityName);
        if (metaClass == null)
            throw new IllegalArgumentException("MetaClass not found for " + entityName);

        Transaction tx = persistence.createTransaction();
        try {
            FtsSender sender = AppBeans.get(FtsSender.NAME);

            sender.emptyQueue(entityName);
            tx.commitRetaining();

            EntityDescr descr = getDescrByName().get(entityName);
            if (descr == null)
                return count;

            EntityManager em = persistence.getEntityManager();

            if (StringUtils.isBlank(descr.getSearchableIfScript())) {
                Query q = em.createQuery("select e.id from " + entityName + " e");
                List<UUID> list = q.getResultList();
                for (UUID id : list) {
                    sender.enqueue(entityName, id, FtsChangeType.INSERT);
                    count++;
                }
            } else {
                Query q = em.createQuery("select e from " + entityName + " e");
                List<Entity> list = q.getResultList();
                for (Entity entity : list) {
                    if (runSearchableIf(entity, descr)) {
                        sender.enqueue(entityName, (UUID) entity.getId(), FtsChangeType.INSERT);
                        count++;
                    }
                }
            }
            tx.commit();
        } finally {
            tx.end();
        }
        return count;
    }

    @Override
    public void asyncReindexEntity(String entityName) {
        MetaClass metaClass = metadata.getSession().getClass(entityName);
        if (metaClass == null)
            throw new IllegalArgumentException("MetaClass not found for " + entityName);

        EntityDescr descr = getDescrByName().get(entityName);
        if (descr == null)
            throw new IllegalArgumentException("FTS configuration not found for " + entityName);

        Transaction tx = persistence.createTransaction();
        try {
            ftsSender.emptyQueue(entityName);
            reindexEntitiesQueue.add(entityName);
            tx.commit();
        } finally {
            tx.end();
        }
    }

    @Override
    public int reindexAll() {
        int count = 0;
        for (String entityName : getDescrByName().keySet()) {
            count += reindexEntity(entityName);
        }
        return count;
    }

    @Override
    public void asyncReindexAll() {
        for (String entityName : getDescrByName().keySet()) {
            asyncReindexEntity(entityName);
        }
    }

    @Override
    public int reindexNextBatch() {
        if (!AppContext.isStarted())
            return 0;

        if (!config.getEnabled())
            return 0;

        if (reindexEntitiesQueue.isEmpty())
            return 0;

        log.debug("Start reindexing next entities batch");
        boolean locked = reindexLock.tryLock();
        if (!locked) {
            log.warn("Unable to reindex next batch of entities: reindexing at the moment");
            return 0;
        }

        authentication.begin();
        Transaction tx = persistence.createTransaction();
        try {
            reindexing = true;
            try {
                String entityName = reindexEntitiesQueue.element();
                int reindexBatchSize = coreConfig.getReindexBatchSize();
                EntityManager em = persistence.getEntityManager();

                EntityDescr entityDescr = getDescrByName().get(entityName);
                String searchableIfScript = entityDescr.getSearchableIfScript();
                if (Strings.isNullOrEmpty(searchableIfScript)) {
                    List<UUID> ids = em.createQuery("select e.id from " + entityName + " e where e.id not in " +
                            "(select q.entityId from sys$FtsQueue q where q.entityName = :entityName)", UUID.class)
                            .setParameter("entityName", entityName)
                            .setMaxResults(reindexBatchSize)
                            .getResultList();
                    for (UUID id : ids) {
                        ftsSender.enqueue(entityName, id, FtsChangeType.INSERT);
                    }
                    tx.commit();
                    if (ids.size() < reindexBatchSize) {
                        reindexEntitiesQueue.remove();
                    }
                    log.debug(ids.size() + " instances of " + entityName + " was added to the FTS queue");
                    return ids.size();
                } else {
                    List<Entity> entities = em.createQuery("select e from " + entityName + " e where e.id not in " +
                            "(select q.entityId from sys$FtsQueue q where q.entityName = :entityName)", Entity.class)
                            .setParameter("entityName", entityName)
                            .setMaxResults(reindexBatchSize)
                            .getResultList();
                    int count = 0;
                    for (Entity entity : entities) {
                        if (runSearchableIf(entity, entityDescr)) {
                            ftsSender.enqueue(entityName, (UUID) entity.getId(), FtsChangeType.INSERT);
                            count++;
                        } else {
                            ftsSender.enqueueFake(entityName, (UUID) entity.getId());
                        }
                    }
                    if (entities.size() < reindexBatchSize) {
                        reindexEntitiesQueue.remove();
                        ftsSender.emptyFakeQueue(entityName);
                    }
                    tx.commit();
                    log.debug(entities.size() + " instances of " + entityName + " was processed. "
                            + count + " of them was added to the FTS queue");
                    return entities.size();

                }
            } finally {
                tx.end();
            }
        } finally {
            reindexLock.unlock();
            reindexing = false;
            authentication.end();
        }
    }

    @Override
    public Directory getDirectory() {
        if (directory == null) {
            synchronized (this) {
                if (directory == null) {
                    String dir = coreConfig.getIndexDir();
                    if (StringUtils.isBlank(dir)) {
                        Configuration configuration = AppBeans.get(Configuration.NAME);
                        dir = configuration.getConfig(GlobalConfig.class).getDataDir() + "/ftsindex";
                    }
                    Path file = Paths.get(dir);
                    if (!Files.exists(file)) {
                        try {
                            Files.createDirectory(file);
                        } catch (IOException e) {
                            throw new RuntimeException("Directory " + dir + " doesn't exist and can not be created");
                        }
                    }
                    try {
                        directory = FSDirectory.open(file);
                        if (Files.exists(file.resolve("write.lock"))) {
                            directory.deleteFile("write.lock");
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return directory;
    }
}