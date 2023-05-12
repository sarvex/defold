#!/usr/bin/env python
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



import os, sys, subprocess, re

BETA_INTRO = """# Defold %s BETA
The latest beta is now released, and we invite those interested in beta testing the new features in the editor and engine to join now.
The beta period will be 2 weeks and the next planned stable release is two weeks from now.

We hope this new workflow will highlight any issues earlier, and also get valuable feedback from our users. And please comment if you come up with ideas on improving on this new workflow.

Please report any engine issues in this thread or in [issues](https://github.com/defold/defold/issues) using Help -> Report Issue

Thx for helping out!

## Disclaimer
This is a BETA release, and it might have issues that could potentially be disruptive for you and your teams workflow. Use with caution. Use of source control for your projects is strongly recommended.

## Access to the beta
Download the editor or bob.jar from http://d.defold.com/beta/

Set your build server to https://build-stage.defold.com

"""

def run(cmd, shell=False):
    p = subprocess.Popen(cmd.split(), stdout=subprocess.PIPE, shell=shell)
    p.wait()
    out, err = p.communicate()
    if p.returncode != 0:
        raise Exception(f"Failed to run: {cmd}")

    return out

def read_version():
    # read the version number from the VERSION file
    with open('VERSION', 'rb') as f:
        d = f.read()
        tokens = d.split('.')
        return map(int, tokens)
    return None

def get_sha1_from_tag(tag):
    return run('git log -1 --format=format:%%H %s' % tag)


def git_log(sha1):
    return run(f"git log {sha1} -1")


def git_merge_desc(sha1):
    s = run(f"git show {sha1}")
    desc = ''
    skip_lines = 1
    for l in s.split('\n'):
        l = l.strip()
        words = l.split()
        if not words:
            continue
        if words[0] in ('Merge:', 'Author:', 'Date:', 'commit'):
            continue
        if skip_lines > 0:
            skip_lines = skip_lines-1
            continue
        return l # we only need first line
    return ""

def match_issue(line):
    if issue_match := re.search(
        "^(?i)([a-fA-F0-9]+) (?:issue[\-\s]?)?#?(\d+)[:.]? (.*)", line
    ):
        sha1 = issue_match[1]
        issue = issue_match[2]
        desc = issue_match[3]
        if m := re.search("^(.*) \(\#\d+\)$", desc):
            desc = m[1]
        return (sha1, issue, desc)
    return (None, None, None)

def match_merge(line):
    if not (
        merge_match := re.search(
            "(\w+)\s(?:Revert\s\")?Merge pull request\s#(\d+)\s.+?(?:Issue|issue[\-]?)?(\d+).+",
            line,
        )
    ):
        return (None, None, None)
    sha1 = merge_match[1]
    pr = merge_match[2]
    issue = merge_match[3]
    desc  = git_merge_desc(sha1)

    if m := re.search(
        "^(?:Issue|issue)(?:[\-\s]?)?#?(\d+)[:.\-\s]+(.+)", desc
    ):
        desc = m[2]
        if not issue:
            issue = m[1]
    return (sha1, issue, desc)


def match_pullrequest(line):
    if pull_match := re.search("([a-fA-F0-9]+) (.*) \(\#(\d+)\)$", line):
        sha1 = pull_match[1]
        desc = pull_match[2]
        pr = pull_match[3]
        return (sha1, pr, desc)
    return (None, None, None)

def get_engine_issues(lines):
    issues = []
    for line in lines:
        (sha1, issue, desc) = match_issue(line)
        if issue:
            issues.append(
                f"[`Issue-{issue}`](https://github.com/defold/defold/issues/{issue}) - **Fixed**: {desc}"
            )
            print(git_log(sha1))
            continue

        (sha1, issue, desc) = match_merge(line)
        if issue:
            issues.append(
                f"[`Issue-{issue}`](https://github.com/defold/defold/issues/{issue}) - **Fixed**: {desc}"
            )

            print(git_log(sha1))
            continue

        (sha1, issue, desc) = match_pullrequest(line)
        if issue:
            issues.append(
                f"[`Issue-{issue}`](https://github.com/defold/defold/issues/{issue}) - **Fixed**: {desc}"
            )
            print(git_log(sha1))
            continue

    return issues

def get_editor_issues(lines):
    issues = []
    for line in lines:
        if m := re.search("^([a-fA-F0-9]+) (.*) \(DEFEDIT-(\d+)\)", line):
            sha1 = m[1]
            desc = m[2]
            issue = m[3]
            issues.append("[`DEFEDIT-%s`](https://github.com/defold/defold/search?q=hash%%3A%s&type=Commits) - **Fixed**: %s" % (issue, sha1, desc))
            print(git_log(sha1))
    return issues

def get_all_changes(version, sha1):
    out = run("git log %s..HEAD --oneline" % sha1)
    lines = out.split('\n')

    print out
    print("#" + "*" * 64)

    engine_issues = get_engine_issues(lines)
    editor_issues = get_editor_issues(lines)

    print("")
    print("#" + "*" * 64)
    print("")
    print(BETA_INTRO % version)
    print("# Engine")

    for issue in sorted(list(set(engine_issues))):
        print("  * " + issue)

    print("# Editor")

    for issue in sorted(list(set(editor_issues))):
        print("  * " + issue)


def get_contributors(tag):
    print("")
    print("")
    print("# Contributors")
    print("")
    print("We'd also like to take the opportunity to thank our community for contributing to the source code.")
    print("This is the number of contributions since the last release.")
    print("")

    r = run(f"scripts/list_contributors.sh {tag}")
    print(r)


if __name__ == '__main__':
    current_version = read_version()
    if current_version is None:
        print >>sys.stderr, "Failed to open VERSION"
        sys.exit(1)

    tag = "%d.%d.%d" % (current_version[0], current_version[1], current_version[2]-1)
    sha1 = get_sha1_from_tag(tag)
    if sha1 is None:
        print >>sys.stderr, "Failed to rad tag '%s'" % tag
        sys.exit(1)

    print("Found previous version", tag, sha1)

    version = "%d.%d.%d" % (current_version[0], current_version[1], current_version[2])
    get_all_changes(version, sha1)

    #get_contributors(tag)
