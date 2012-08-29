/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.elasticsearch.river.jira;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.search.SearchHit;

/**
 * Class used to run one index update process for one JIRA project. Can be used only for one run, then must be discarded
 * and new instance created!
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public class JIRAProjectIndexer implements Runnable {

  private static final ESLogger logger = Loggers.getLogger(JIRAProjectIndexer.class);

  /**
   * Property value where "last indexed issue update date" is stored
   * 
   * @see IESIntegration#storeDatetimeValue(String, String, Date, BulkRequestBuilder)
   * @see IESIntegration#readDatetimeValue(String, String)
   */
  protected static final String STORE_PROPERTYNAME_LAST_INDEXED_ISSUE_UPDATE_DATE = "lastIndexedIssueUpdateDate";

  protected final IJIRAClient jiraClient;

  protected final IESIntegration esIntegrationComponent;

  /**
   * Configured JIRA issue index structure builder to be used.
   */
  protected final IJIRAIssueIndexStructureBuilder jiraIssueIndexStructureBuilder;

  /**
   * Key of JIRA project updated.
   */
  protected final String projectKey;

  /**
   * <code>true</code> if full update indexing is necessary, <code>false</code> on incremental update.
   */
  protected boolean fullUpdate = false;

  /**
   * Time when indexing started.
   */
  protected long startTime = 0;

  /**
   * Number of issues updated during this indexing.
   */
  protected int updatedCount = 0;

  /**
   * Number of issues deleted during this indexing.
   */
  protected int deleteCount = 0;

  /**
   * @param projectKey JIRA project key for project to be indexed by this indexer.
   * @param jiraClient configured JIRA client to be used to obtain informations from JIRA.
   * @param esIntegrationComponent to be used to call River component and ElasticSearch functions
   */
  public JIRAProjectIndexer(String projectKey, boolean fullUpdate, IJIRAClient jiraClient,
      IESIntegration esIntegrationComponent, IJIRAIssueIndexStructureBuilder jiraIssueIndexStructureBuilder) {
    if (projectKey == null || projectKey.trim().length() == 0)
      throw new IllegalArgumentException("projectKey must be defined");
    this.jiraClient = jiraClient;
    this.projectKey = projectKey;
    this.fullUpdate = fullUpdate;
    this.esIntegrationComponent = esIntegrationComponent;
    this.jiraIssueIndexStructureBuilder = jiraIssueIndexStructureBuilder;
  }

  @Override
  public void run() {
    startTime = System.currentTimeMillis();
    try {
      processUpdate();
      processDelete(new Date(startTime));
      long timeElapsed = (System.currentTimeMillis() - startTime);
      esIntegrationComponent.reportIndexingFinished(projectKey, true, fullUpdate, updatedCount, deleteCount, new Date(
          startTime), timeElapsed, null);
      logger.info("Finished {} update for JIRA project {}. {} updated and {} deleted issues. Time elapsed {}s.",
          fullUpdate ? "full" : "incremental", projectKey, updatedCount, deleteCount, (timeElapsed / 1000));
    } catch (Throwable e) {
      long timeElapsed = (System.currentTimeMillis() - startTime);
      esIntegrationComponent.reportIndexingFinished(projectKey, false, fullUpdate, updatedCount, deleteCount, new Date(
          startTime), timeElapsed, e.getMessage());
      logger.error("Failed {} update for JIRA project {} due: {}", e, fullUpdate ? "full" : "incremental", projectKey,
          e.getMessage());
    }
  }

  /**
   * Process update of search index for configured JIRA project. A {@link #updatedCount} field is updated inside of this
   * method. A {@link #fullUpdate} field can be updated inside of this method.
   * 
   * @throws Exception
   * 
   */
  @SuppressWarnings("unchecked")
  protected void processUpdate() throws Exception {
    updatedCount = 0;
    Date updatedAfter = Utils.roundDateToMinutePrecise(readLastIssueUpdatedDate(projectKey));
    logger.info("Go to process JIRA updates for project {} for issues updated {}", projectKey,
        (updatedAfter != null ? ("after " + updatedAfter) : "in whole history"));
    Date updatedAfterStarting = updatedAfter;
    if (updatedAfter == null)
      fullUpdate = true;
    Date lastIssueUpdatedDate = null;
    int startAt = 0;

    boolean cont = true;
    while (cont) {
      if (isClosed())
        return;

      if (logger.isDebugEnabled())
        logger.debug("Go to ask JIRA issues for project {} with startAt {} updated {}", projectKey, startAt,
            (updatedAfter != null ? ("after " + updatedAfter) : "in whole history"));

      ChangedIssuesResults res = jiraClient.getJIRAChangedIssues(projectKey, startAt, updatedAfter, null);

      if (res.getIssuesCount() == 0) {
        cont = false;
      } else {
        String firstIssueUpdated = null;
        String lastIssueUpdated = null;
        BulkRequestBuilder esBulk = esIntegrationComponent.getESBulkRequestBuilder();
        for (Map<String, Object> issue : res.getIssues()) {
          String issueKey = XContentMapValues.nodeStringValue(issue.get(JIRA5RestIssueIndexStructureBuilder.JF_KEY),
              null);
          if (issueKey == null) {
            throw new IllegalArgumentException("'key' field not found in JIRA data");
          }
          lastIssueUpdated = XContentMapValues.nodeStringValue(((Map<String, Object>) issue
              .get(JIRA5RestIssueIndexStructureBuilder.JF_FIELDS)).get(JIRA5RestIssueIndexStructureBuilder.JF_UPDATED),
              null);
          logger.debug("Go to update index for issue {} with updated {}", issueKey, lastIssueUpdated);
          if (lastIssueUpdated == null) {
            throw new IllegalArgumentException("'updated' field not found in JIRA data for key " + issueKey);
          }
          if (firstIssueUpdated == null) {
            firstIssueUpdated = lastIssueUpdated;
          }

          jiraIssueIndexStructureBuilder.indexIssue(esBulk, projectKey, issue);
          updatedCount++;
          if (isClosed())
            break;
        }

        lastIssueUpdatedDate = Utils.parseISODateWithMinutePrecise(lastIssueUpdated);

        storeLastIssueUpdatedDate(esBulk, projectKey, lastIssueUpdatedDate);
        esIntegrationComponent.executeESBulkRequestBuilder(esBulk);

        // next logic depends on issues sorted by update time ascending when returned from
        // jiraClient.getJIRAChangedIssues()!!!!
        if (!lastIssueUpdatedDate.equals(Utils.parseISODateWithMinutePrecise(firstIssueUpdated))) {
          // processed issues updated in different times, so we can continue by issue filtering based on latest time
          // of update which is more safe for concurrent changes in JIRA
          updatedAfter = lastIssueUpdatedDate;
          cont = res.getTotal() > (res.getStartAt() + res.getIssuesCount());
          startAt = 0;
        } else {
          // more issues updated in same time, we must go over them using pagination only, which may sometimes lead
          // to some issue update lost due concurrent changes in JIRA
          startAt = res.getStartAt() + res.getIssuesCount();
          cont = res.getTotal() > startAt;
        }
      }
    }

    if (updatedCount > 0 && lastIssueUpdatedDate != null && updatedAfterStarting != null
        && updatedAfterStarting.equals(lastIssueUpdatedDate)) {
      // no any new issue during this update cycle, go to increment lastIssueUpdatedDate in store by one minute not to
      // index last issue again and again in next cycle - this is here due JQL minute precise on timestamp search
      storeLastIssueUpdatedDate(null, projectKey,
          Utils.roundDateToMinutePrecise(new Date(lastIssueUpdatedDate.getTime() + 64 * 1000)));
    }
  }

  /**
   * Process delete of issues from search index for configured JIRA project. A {@link #deleteCount} field is updated
   * inside of this method.
   * 
   * @param boundDate date when full update was started. We delete all search index documents not updated after this
   *          date (which means these issues are not in jira anymore).
   */
  private void processDelete(Date boundDate) throws Exception {
    deleteCount = 0;
    if (!fullUpdate)
      return;

    String indexName = jiraIssueIndexStructureBuilder.getIssuesSearchIndexName(projectKey);
    esIntegrationComponent.refreshSearchIndex(indexName);

    logger.debug("go to delete indexed issues for project {} not updated after {}", projectKey, boundDate);
    SearchRequestBuilder srb = esIntegrationComponent.prepareESScrollSearchRequestBuilder(indexName);
    jiraIssueIndexStructureBuilder.searchForIndexedIssuesNotUpdatedAfter(srb, projectKey, boundDate);

    SearchResponse scrollResp = srb.execute().actionGet();

    BulkRequestBuilder esBulk = esIntegrationComponent.getESBulkRequestBuilder();
    while (scrollResp.hits().hits().length > 0) {
      for (SearchHit hit : scrollResp.getHits()) {
        deleteCount++;
        logger.debug("go to delete indexed issue {}", hit.getId());
        jiraIssueIndexStructureBuilder.deleteIssue(esBulk, hit.getId());
      }
      scrollResp = esIntegrationComponent.performESScrollSearchNextRequest(scrollResp);
    }
    if (deleteCount > 0)
      esIntegrationComponent.executeESBulkRequestBuilder(esBulk);

  }

  /**
   * Check if we must interrupt update process because ElasticSearch runtime needs it.
   * 
   * @return true if we must interrupt update process
   */
  protected boolean isClosed() {
    return esIntegrationComponent != null && esIntegrationComponent.isClosed();
  }

  /**
   * Get date of last issue updated for given JIRA project from persistent store inside ES cluster, so we can continue
   * in update process from this point.
   * 
   * @param jiraProjectKey JIRA project key to get date for.
   * @return date of last issue updated or null if not available (in this case indexing starts from the beginning of
   *         project history)
   * @throws IOException
   * @see #storeLastIssueUpdatedDate(BulkRequestBuilder, String, Date)
   */
  protected Date readLastIssueUpdatedDate(String jiraProjectKey) throws Exception {
    return esIntegrationComponent.readDatetimeValue(jiraProjectKey, STORE_PROPERTYNAME_LAST_INDEXED_ISSUE_UPDATE_DATE);
  }

  /**
   * Store date of last issue updated for given JIRA project into persistent store inside ES cluster, so we can continue
   * in update process from this point next time.
   * 
   * @param esBulk ElasticSearch bulk request to be used for update
   * @param jiraProjectKey JIRA project key to store date for.
   * @param lastIssueUpdatedDate date to store
   * @throws Exception
   * @see #readLastIssueUpdatedDate(String)
   */
  protected void storeLastIssueUpdatedDate(BulkRequestBuilder esBulk, String jiraProjectKey, Date lastIssueUpdatedDate)
      throws Exception {
    esIntegrationComponent.storeDatetimeValue(jiraProjectKey, STORE_PROPERTYNAME_LAST_INDEXED_ISSUE_UPDATE_DATE,
        lastIssueUpdatedDate, esBulk);
  }

}
