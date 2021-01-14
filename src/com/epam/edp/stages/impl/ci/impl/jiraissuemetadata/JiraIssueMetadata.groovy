/* Copyright 2020 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

package com.epam.edp.stages.impl.ci.impl.jiraissuemetadata

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.github.jenkins.lastchanges.pipeline.LastChangesPipelineGlobal
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import hudson.FilePath

@Stage(name = "create-jira-issue-metadata", buildTool = ["maven", "npm", "dotnet", "gradle", "any"], type = [ProjectType.APPLICATION, ProjectType.AUTOTESTS, ProjectType.LIBRARY])
class JiraIssueMetadata {
    Script script

    def getChanges(workDir) {
        script.dir("${workDir}") {
            def publisher = new LastChangesPipelineGlobal(script).getLastChangesPublisher "LAST_SUCCESSFUL_BUILD", "SIDE", "LINE", true, true, "", "", "", "", ""
            publisher.publishLastChanges()
            return publisher.getLastChanges()
        }
    }

    def getJiraIssueMetadataCrTemplate(platform) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadata CR template")
        def temp = platform.getJsonPathValue("cm", "jim-template", ".data.jim\\.json")
        script.println("[JENKINS][DEBUG] JiraIssueMetadata template has been fetched ${temp}")
        return new JsonSlurperClassic().parseText(temp)
    }

    def getJiraIssueMetadataPayload(name) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadataPayload of ${name} Codebase CR")
        def payload = platform.getJsonPathValue("codebases", name, ".spec.jiraIssueMetadataPayload")
        script.println("[JENKINS][DEBUG] JiraIssueMetadataPayload of ${name} Codebase CR has been fetched - ${payload}")
        return JSON.parse(payload)
    }

    def addCommitId(template, id) {
        if (template.spec.commits == "replace") {
            template.spec.commits = []
        }
        template.spec.commits.add(id)
    }

    def addTicketNumber(template, tickets) {
        if (template.spec.tickets == "replace") {
            template.spec.tickets = []
        }
        template.spec.tickets.addAll(tickets)
    }

    def parseJiraIssueMetadataTemplate(template, templateParams, commits, ticketNamePattern, commitMsgPattern) {
        script.println("[JENKINS][DEBUG] Parsing JiraIssueMetadata template")
        template.metadata.name = "${templateParams['codebaseName']}-${templateParams['isTag']}".toLowerCase()
        template.spec.codebaseName = templateParams['codebaseName']
        for (commit in commits) {
            def info = commit.getCommitInfo()
            script.println("[JENKINS][DEBUG] Commit message ${info.getCommitMessage()}")
            def tickets = info.getCommitMessage().findAll(ticketNamePattern)
            def id = info.getCommitId()
            if (!tickets) {
                script.println("[JENKINS][DEBUG] No found tickets in ${id} commit")
                continue
            }
            addCommitId(template, id)
            addTicketNumber(template, tickets)

            def messages = info.getCommitMessage().findAll(commitMsgPattern)
            script.println("[JENKINS][DEBUG] messages ${messages}")
        }
        script.println("[JENKINS][DEBUG] parsed template1 ${template}")

        template.spec.payload = getPayloadField(templateParams['codebaseName'], templateParams['isTag'], templateParams['vcsTag'])
        return JsonOutput.toJson(template)
    }

    def getPayloadField(component, version, gitTag) {
        def jsonPayload = getJiraIssueMetadataPayload(component)
        /*if (jsonPayload == null) {

        }
*/
        def values = [
                EDP_COMPONENT: component,
                EDP_VERSION  : version,
                EDP_GITTAG   : gitTag]
        def payload = new JsonSlurperClassic().parseText(jsonPayload)
        payload.each {
            values.each {
                k, v -> it.value = it.value.replaceAll(k, v)
            }
        }
  //      payload['links'] =
        return JsonOutput.toJson(payload)
    }

    def createJiraIssueMetadataCR(platform, path) {
        script.println("[JENKINS][DEBUG] Trying to create JiraIssueMetadata CR")
        platform.apply(path.getRemote())
        script.println("[JENKINS][INFO] JiraIssueMetadata CR has been created")
    }

    def saveTemplateToFile(outputFilePath, template) {
        def jiraIssueMetadataTemplateFile = new FilePath(Jenkins.getInstance().
                getComputer(script.env['NODE_NAME']).
                getChannel(), outputFilePath)
        jiraIssueMetadataTemplateFile.write(template, null)
        return jiraIssueMetadataTemplateFile
    }

    def tryToCreateJiraIssueMetadataCR(workDir, platform, template) {
        if (new JsonSlurperClassic().parseText(template).spec.tickets == "replace") {
            script.println("[JENKINS][DEBUG] No changes. Skip creating JiraIssueMetadata CR")
            return
        }
        def filePath = saveTemplateToFile("${workDir}/jim-template.json", template)
        createJiraIssueMetadataCR(platform, filePath)
    }

    void run(context) {
        try {
            def ticketNamePattern = context.codebase.config.ticketNamePattern
            script.println("[JENKINS][DEBUG] context.codebase.config ${context.codebase.config}")
            script.println("[JENKINS][DEBUG] Ticket name pattern has been fetched ${ticketNamePattern}")
            def changes = getChanges(context.workDir)
            def commits = changes.getCommits()
            if (commits == null) {
                script.println("[JENKINS][INFO] No changes since last successful build. Skip creating JiraIssueMetadata CR")
            } else {
                def template = getJiraIssueMetadataCrTemplate(context.platform)
                script.println("[JENKINS][DEBUG] jim template ${template}")
                def templateParams = [
                        'codebaseName': context.codebase.config.name,
                        'vcsTag'      : context.codebase.vcsTag,
                        'isTag'       : context.codebase.isTag
                ]
                script.println("[JENKINS][DEBUG] templateParams ${templateParams}")
                def commitMsgPattern = context.codebase.config.commitMessagePattern
                script.println("[JENKINS][DEBUG] commitMsgPattern ${commitMsgPattern}")
                def parsedTemplate = parseJiraIssueMetadataTemplate(template, templateParams, commits, ticketNamePattern, commitMsgPattern)
                tryToCreateJiraIssueMetadataCR(context.workDir, context.platform, parsedTemplate)
            }
        } catch (Exception ex) {
            script.println("[JENKINS][WARNING] Couldn't correctly finish 'create-jira-issue-metadata' stage due to exception: ${ex}")
        }
    }

}