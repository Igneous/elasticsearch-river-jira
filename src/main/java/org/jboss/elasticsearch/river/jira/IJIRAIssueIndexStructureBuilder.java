/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.elasticsearch.river.jira;

import java.util.Date;
import java.util.Map;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.search.SearchHit;
import org.jboss.elasticsearch.river.jira.preproc.IssueDataPreprocessor;

/**
 * Interface for component responsible to transform issue data obtained from JIRA instance call to the document stored
 * in ElasticSearch index. Implementation of this interface must be thread safe!
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public interface IJIRAIssueIndexStructureBuilder {

  /**
   * Add preprocessor to be used during data issue indexing in {@link #indexIssue(BulkRequestBuilder, String, Map)}.
   * Config time method.
   * 
   * @param preprocessor to ass
   */
  void addIssueDataPreprocessor(IssueDataPreprocessor preprocessor);

  /**
   * Get name of search index where issues are stored for given jira project
   * 
   * @return search index name
   */
  String getIssuesSearchIndexName(String jiraProjectKey);

  /**
   * Get issue fields required from JIRA to build index document. Used to construct JIRA request.
   * 
   * @return comma separated list of fields
   */
  String getRequiredJIRACallIssueFields();

  /**
   * Get key for issue from data obtained from JIRA.
   * 
   * @param issue data obtained from JIRA to be indexed (JSON parsed into Map of Map structure)
   * @return
   */
  String extractIssueKey(Map<String, Object> issue);

  /**
   * Get date of last issue update from data obtained from JIRA.
   * 
   * @param issue data obtained from JIRA to be indexed (JSON parsed into Map of Map structure)
   * @return date of last update
   */
  Date extractIssueUpdated(Map<String, Object> issue);

  /**
   * Store/Update issue obtained from JIRA in search index.
   * 
   * @param esBulk bulk operation builder used to update issue data in search index
   * @param jiraProjectKey JIRA project key indexed issue is for
   * @param issue data obtained from JIRA to be indexed (JSON parsed into Map of Map structure)
   * @throws Exception
   */
  void indexIssue(BulkRequestBuilder esBulk, String jiraProjectKey, Map<String, Object> issue) throws Exception;

  /**
   * Construct search request to find issues and comment indexed documents not updated after given date. Used during
   * full index update to remove issues not presented in JIRA anymore. Results from this query are processed by
   * {@link #deleteIssueDocument(BulkRequestBuilder, SearchHit)}
   * 
   * @param srb search request builder to add necessary conditions into
   * @param jiraProjectKey key of jira project to search issues for
   * @param date bound date for search. All issues updated before this date must be found by constructed query
   */
  void buildSearchForIndexedDocumentsNotUpdatedAfter(SearchRequestBuilder srb, String jiraProjectKey, Date date);

  /**
   * Delete issues related document (issue or comment document) from search index. Query to obtain documents to be
   * deleted is constructed using
   * {@link #buildSearchForIndexedDocumentsNotUpdatedAfter(SearchRequestBuilder, String, Date)}
   * 
   * @param esBulk bulk operation builder used to delete data from search index
   * @param issueDocumentToDelete found issue or comment document to delete from index
   * @return true if deleted document is issue, else otherwise (eg. if deleted document is comment)
   * @throws Exception
   */
  boolean deleteIssueDocument(BulkRequestBuilder esBulk, SearchHit issueDocumentToDelete) throws Exception;

}
