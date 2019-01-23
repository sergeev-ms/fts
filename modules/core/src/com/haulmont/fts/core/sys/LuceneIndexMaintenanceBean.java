/*
 * Copyright (c) 2008-2019 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.fts.core.sys;

import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.security.app.Authentication;
import com.haulmont.fts.global.FtsConfig;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.lucene.index.IndexUpgrader;
import org.apache.lucene.index.IndexWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.IOException;

@Component(LuceneIndexMaintenance.NAME)
public class LuceneIndexMaintenanceBean implements LuceneIndexMaintenance{

    @Inject
    protected FtsConfig ftsConfig;

    @Inject
    protected IndexWriterProvider indexWriterProvider;

    @Inject
    protected DirectoryProvider directoryProvider;

    @Inject
    protected Authentication authentication;

    private final Logger log = LoggerFactory.getLogger(LuceneIndexMaintenanceBean.class);

    @Override
    public String optimize() {
        if (!AppContext.isStarted())
            return "Application is not started";
        if (!ftsConfig.getEnabled())
            return "FTS is disabled";

        log.debug("Start optimize");
        authentication.begin();
        try {
            IndexWriter indexWriter = indexWriterProvider.getIndexWriter();
            indexWriter.forceMerge(1);
            indexWriter.commit();
            return "Done";
        } catch (Throwable e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        } finally {
            authentication.end();
        }
    }

    @Override
    public String upgrade() {
        IndexUpgrader upgrader = new IndexUpgrader(directoryProvider.getDirectory());
        try {
            upgrader.upgrade();
        } catch (IOException e) {
            log.error("Error", e);
            return ExceptionUtils.getStackTrace(e);
        }
        return "successful";
    }
}
