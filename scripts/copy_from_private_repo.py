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



import os, sys, shutil, subprocess

PLATFORMS=[]
for p in ['nx64', 'ps4', 'ps5']:
    PLATFORMS.extend((p, p.upper()))
FILE_PATTERNS = [
    'private.py',
    'private.sh',
    'private.yml',
    'private.appmanifest',
    '.appmanifest',
    'SwitchBundler.java',
    'switch',
    'ps4',
    'ps5',
    'meta.edn',
    'meta.properties',
    'build.xml',
]
#FILE_PATTERNS.append('com.dynamo.cr.bob') # TODO: until we've fixed the above bob cases

LOCAL_PATTERNS = [
    '.pyc',
    '.git/',
    'generated/',
    'dist/',
    'build/',
    'editor/target/classes/',
    'dynamo_home',
]

def is_local_file(path):
    return any(pattern in path for pattern in LOCAL_PATTERNS)

def is_private_file(path):
    for pattern in PLATFORMS+FILE_PATTERNS:
        if pattern in path:
            print("Skipping", path)
            return True
    return False

def is_git_tracked(path, cwd):
    #oldcwd = os.getcwd()
    #os.chdir(cwd)
    cmd = f'git ls-files --error-unmatch {path}'
    #r = os.system(cmd)

    process = subprocess.Popen(cmd.split(), cwd=cwd)
    process.wait()
    #output = process.communicate()[0]
    return process.returncode == 0

    #os.chdir(oldcwd)
    #return r == 0

def copy_file(src, tgt):
    dirname = os.path.dirname(tgt)
    if not os.path.exists(dirname):
        os.makedirs(dirname)
    shutil.copy2(src, tgt)

def Usage():
    print("Usage: ./copy_from_private_repo.py <src> <tgt>")

if __name__ == '__main__':

    if len(sys.argv) < 3:
        Usage()
        exit(1)

    src = sys.argv[1]
    tgt = sys.argv[2]

    src = os.path.normpath(src)
    tgt = os.path.normpath(tgt)

    for root, dirs, files in os.walk(src):
        for f in files:
            path = os.path.join(root, f)
            path = path.replace('\\', '/')

            relative_path = os.path.relpath(path, src)
            relative_path = relative_path.replace('\\', '/')

            if is_local_file(relative_path):
                continue
            if is_private_file(relative_path):
                continue

            tgtfile = f'{tgt}/{relative_path}'

            #print "path", path
            #print "relative_path", relative_path
            #print "tgtfile", tgtfile

            if not is_git_tracked(relative_path, src):
                continue

            copy_file(path, tgtfile)

                    #exit(1)

    print("Done!")
