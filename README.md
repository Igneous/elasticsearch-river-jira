JIRA River Plugin for ElasticSearch
===================================

The JIRA River Plugin allows index [Atlassian JIRA](http://www.atlassian.com/software/jira) issues and issue comments into [ElasticSearch](http://www.elasticsearch.org). It's implemented as ElasticSearch [river](http://www.elasticsearch.org/guide/reference/river/) [plugin](http://www.elasticsearch.org/guide/reference/modules/plugins.html) and uses [JIRA REST API](https://developer.atlassian.com/display/JIRADEV/JIRA+REST+APIs) to obtain issus from JIRA instance.

**This plugin is in very early alpha phase of development!!**

In order to install the plugin into ElasticSearch, simply run: `bin/plugin -install elasticsearch/elasticsearch-river-jira/1.0.0`.

    ----------------------------------------------------------------------------
    | JIRA Plugin    | ElasticSearch    | JIRA version | JIRA REST API version |
    ----------------------------------------------------------------------------
    | master         | 0.19 -> master   | 5+           | 2                     |
    ----------------------------------------------------------------------------

The JIRA river indexes JIRA issues and comments, and makes it searchable by ElasticSearch.

Creating the twitter river can be done using:

	curl -XPUT localhost:9200/_river/my_jira_river/_meta -d '
	{
	    "type" : "jira",
	    "jira" : {
	        "urlBase"  : "https://issues.jboss.org",
	        "username" : "jira_username",
	        "pwd"      : "jira_user_password",
	    },
	    "index" : {
	        "index" : "my_jira_index",
	    }
	}
	'

The above lists all the options controlling the creation of a JIRA river. 
The `jira/urlBase` is required in order to connect to the JIRA REST API. It's only base URL, path to REST API is added automatically.
The `jira/username` and `jira/pwd` are optional, Anonymous access to JIRA is used if not provided.

The `index/index` defines name of search index where JIRA issues are stored. Parameter is optional, name of river is used if ommited.

TODO List
---------
* Initial full indexing of all issues
* Incremental JIRA issue adds/edits indexing (pooling used with configurable changes checking period)
* Incremental JIRA issue delete indexing (all keys list comparation with configurable checking period)
* Configurable list of JIRA project keys which may be indexed. All projects indexed if 'include' list not defined with possibility of 'exclude' list.
* Credentials for http proxy authentication used for indexation
* Configurable number of parallel threads used for JIRA indexation to speed it up a little but not to DOS JIRA instance.
* Configurable list of additional JIRA issue fields indexed (some basic fields as Reporter, Assignee, Created, Updsated, Summary and Description will be indexed by default)
* Implement some mechanism which allows to initiate full reindex of all issues (calleable over REST)
* Implement some mechanism which allows to initiate full reindex of all issues for defined JIRA project (calleable over REST)


License
-------

    This software is licensed under the Apache 2 license, quoted below.

    Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors as indicated by the @authors tag. 
    All rights reserved.

    Licensed under the Apache License, Version 2.0 (the "License"); you may not
    use this file except in compliance with the License. You may obtain a copy of
    the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
    License for the specific language governing permissions and limitations under
    the License.
