/*
 * Copyright (c) 2008-2016 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/commercial-software-license for details.
 */
package com.haulmont.fts.core.app;

import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.fts.core.sys.EntityDescr;
import org.apache.lucene.store.Directory;

import java.util.List;
import java.util.Map;
import java.util.Queue;

public interface FtsManagerAPI {

    String NAME = "cuba_FtsManager";

    Directory getDirectory();

    List<Entity> getSearchableEntities(Entity entity);

    boolean isReindexing();

    Queue<String> getReindexEntitiesQueue();

    Map<String, EntityDescr> getDescrByName();

    int processQueue();

    String optimize();

    String upgrade();

    boolean showInResults(String entityName);

    boolean isEnabled();

    void setEnabled(boolean value);

    boolean isWriting();

    void deleteIndexForEntity(String entityName);

    void deleteIndex();

    int reindexEntity(String entityName);

    int reindexAll();

    void asyncReindexEntity(String entityName);

    void asyncReindexAll();

    int reindexNextBatch();
}
