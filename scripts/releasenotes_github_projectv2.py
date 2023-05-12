#!/usr/bin/env python3
# Copyright 2020-2023 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
# 
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
# 
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

# add build_tools folder to the import search path
import sys, os, io, re
from os.path import join, dirname, basename, relpath, expanduser, normpath, abspath
sys.path.append(os.path.join(normpath(join(dirname(abspath(__file__)), '..')), "build_tools"))

import optparse
import github
import json

token = None

TYPE_BREAKING_CHANGE = "BREAKING CHANGE"
TYPE_FIX = "FIX"
TYPE_NEW = "NEW"

# https://docs.github.com/en/graphql/overview/explorer
QUERY_CLOSED_ISSUES = r"""
{
  organization(login: "defold") {
    id
    projectV2(number: %s) {
      id
      title
      items(first: 100) {
        nodes {
          content {
            ... on Issue {
              id
              closed
              title
              bodyText
            }
          }
        }
      }
    }
  }
}
"""

QUERY_PROJECT_ISSUES_AND_PRS = r"""
{
  organization(login: "defold") {
    id
    projectV2(number: %s) {
      id
      title
      items(first: 100) {
        nodes {
          type
          content {
            ... on Issue {
              id
              closed
              title
              number
              body
              url
              labels(first: 10) {
                nodes {
                  name
                }
              }
              timelineItems(first: 100) {
                nodes {
                  ... on CrossReferencedEvent {
                    source {
                      ... on PullRequest {
                        id
                        body
                        number
                        merged
                        title
                        url
                        labels(first: 10) {
                          nodes {
                            name
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            ... on PullRequest {
              id
              merged
              title
              number
              body
              url
              labels(first: 10) {
                nodes {
                  name
                }
              }
              timelineItems(first: 100) {
                nodes {
                  ... on CrossReferencedEvent {
                    source {
                      ... on Issue {
                        id
                        body
                        number
                        closed
                        title
                        url
                        labels(first: 10) {
                          nodes {
                            name
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
"""

QUERY_PROJECT_NUMBER = r"""
{
    organization(login: "defold") {
        projectsV2(first: 1, query: "%s") {
            nodes {
                id
                title
                number
            }
        }
    }
}
"""

def pprint(d):
    print(json.dumps(d, indent=4, sort_keys=True))

def get_project(name):
    query = QUERY_PROJECT_NUMBER % name
    response = github.query(query, token)
    return response["data"]["organization"]["projectsV2"]["nodes"][0]

def get_issues_and_prs(project):
    query = QUERY_PROJECT_ISSUES_AND_PRS % project.get("number")
    response = github.query(query, token)
    response = response["data"]["organization"]["projectV2"]["items"]["nodes"]
    return response

def get_labels(issue_or_pr):
    return [l["name"] for l in issue_or_pr["labels"]["nodes"]]

def get_issue_type(issue):
    labels = get_labels(issue)
    if "breaking change" in labels:
        return TYPE_BREAKING_CHANGE
    elif "bug" in labels:
        return TYPE_FIX
    elif "task" in labels or "feature request" in labels:
        return TYPE_NEW
    return TYPE_FIX

def get_closing_pr(issue):
    return next(
        (
            t["source"]
            for t in issue["timelineItems"]["nodes"]
            if "source" in t and t["source"]
        ),
        issue,
    )

def issue_to_markdown(issue, hide_details = True, title_only = False):
    if title_only:
        md = ("* __%s__: ([#%s](%s)) %s \n" % (issue["type"], issue["number"], issue["url"], issue["title"]))

    else:    
        md = ("__%s__: ([#%s](%s)) __%s__ \n" % (issue["type"], issue["number"], issue["url"], issue["title"]))
        if hide_details: md += ("[details=\"Details\"]\n")
        md += ("%s\n" % issue["body"])
        if hide_details: md += ("\n---\n[/details]\n")
        md += ("\n")

    return md

def generate(version, hide_details = False):
    print(f"Generating release notes for {version}")
    project = get_project(version)
    if not project:
        print(f"Unable to find GitHub project for version {version}")
        return None

    output = []
    merged = get_issues_and_prs(project)
    for m in merged:
        content = m.get("content")
        if not content:
            continue
        is_issue = m.get("type") == "ISSUE"
        is_pr = m.get("type") == "PULL_REQUEST"
        if is_issue and content.get("closed") == False:
            continue
        if is_pr and content.get("merged") == False:
            continue

        issue_labels = get_labels(content)
        if "skip release notes" in issue_labels:
            continue

        issue_type = get_issue_type(content)
        if is_issue:
            content = get_closing_pr(content)

        entry = {
            "title": content.get("title"),
            "body": content.get("body"),
            "url": content.get("url"),
            "number": content.get("number"),
            "labels": issue_labels,
            "is_pr": is_pr,
            "is_issue": is_issue,
            "type": issue_type
        }
        # strip from match to end of file
        entry["body"] = re.sub("## PR checklist.*", "", entry["body"], flags=re.DOTALL).strip()
        entry["body"] = re.sub("### Technical changes.*", "", entry["body"], flags=re.DOTALL).strip()
        entry["body"] = re.sub("Technical changes:.*", "", entry["body"], flags=re.DOTALL).strip()
        entry["body"] = re.sub("Technical notes:.*", "", entry["body"], flags=re.DOTALL).strip()

        # Remove closing keywords
        entry["body"] = re.sub("Fixes .*/.*#.....*", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("Fix .*/.*#.....*", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("Fixes #.....*", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("Fix #.....*", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("Fixes https.*", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("Fix https.*", "", entry["body"], flags=re.IGNORECASE).strip()

        # Remove "user facing changes" header
        entry["body"] = re.sub("User-facing changes:", "", entry["body"], flags=re.IGNORECASE).strip()
        entry["body"] = re.sub("### User-facing changes", "", entry["body"], flags=re.IGNORECASE).strip()

        duplicate = any(o.get("number") == entry.get("number") for o in output)
        if not duplicate:
            output.append(entry)

    engine = []
    editor = []
    for o in output:
        if "editor" in o["labels"]:
            editor.append(o)
        else:
            engine.append(o)

    types = [ TYPE_BREAKING_CHANGE, TYPE_NEW, TYPE_FIX ]
    summary = ("\n## Summary\n")
    details_engine = ("\n## Engine\n")
    details_editor = ("\n## Editor\n")
    for issue_type in types:
        for issue in engine:
            if issue["type"] == issue_type:
                summary += issue_to_markdown(issue, title_only = True)
                details_engine += issue_to_markdown(issue, hide_details = hide_details)
        for issue in editor:
            if issue["type"] == issue_type:
                summary += issue_to_markdown(issue, title_only = True)
                details_editor += issue_to_markdown(issue, hide_details = hide_details)

    content = ("# Defold %s\n" % version) + summary + details_engine + details_editor
    with io.open(f"releasenotes-forum-{version}.md", "wb") as f:
        f.write(content.encode('utf-8'))


if __name__ == '__main__':
    usage = '''usage: %prog [options] command(s)

Commands:
generate - Generate release notes
'''
    parser = optparse.OptionParser(usage)

    parser.add_option('--version', dest='version',
                      default = None,
                      help = 'Version to genereate release notes for')

    parser.add_option('--token', dest='token',
                      default = None,
                      help = 'GitHub API topken')

    parser.add_option('--hide-details', dest='hide_details',
                      default = False,
                      action = "store_true",
                      help = 'Hide details for each entry')


    options, args = parser.parse_args()

    if not args:
        parser.print_help()
        exit(1)

    if not options.token:
        print("No token specified")
        parser.print_help()
        exit(1)

    if not options.version:
        print("No version specified")
        parser.print_help()
        exit(1)

    token = options.token
    for cmd in args:
        if cmd == "generate":
            generate(options.version, options.hide_details)


    print('Done')
