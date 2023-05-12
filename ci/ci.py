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

import sys
import subprocess
import platform
import os
import base64
from argparse import ArgumentParser
from ci_helper import is_platform_supported, is_repo_private

# The platforms we deploy our editor on
PLATFORMS_DESKTOP = ('x86_64-linux', 'x86_64-win32', 'x86_64-macos')

def call(args, failonerror = True):
    print(args)
    process = subprocess.Popen(args, stdout = subprocess.PIPE, stderr = subprocess.STDOUT, shell = True)

    output = ''
    while True:
        line = process.stdout.readline().decode()
        if line == '':
            break

        output += line
        print(line.rstrip())
    if process.wait() != 0 and failonerror:
        exit(1)

    return output


def platform_from_host():
    system = platform.system()
    if system == "Linux":
        return "x86_64-linux"
    elif system == "Darwin":
        return "x86_64-macos"
    else:
        return "x86_64-win32"

def aptget(package):
    call(f"sudo apt-get install -y --no-install-recommends {package}")

def aptfast(package):
    call(f"sudo apt-fast install -y --no-install-recommends {package}")

def choco(package):
    call(f"choco install {package} -y")


def mingwget(package):
    call(f"mingw-get install {package}")


def setup_keychain(args):
    print("Setting up keychain")
    keychain_pass = "foobar"
    keychain_name = "defold.keychain"

    # create new keychain
    print("Creating keychain")
    # call("security delete-keychain {}".format(keychain_name))
    call(f"security create-keychain -p {keychain_pass} {keychain_name}")

    # set the new keychain as the default keychain
    print("Setting keychain as default")
    call(f"security default-keychain -s {keychain_name}")

    # unlock the keychain
    print("Unlock keychain")
    call(f"security unlock-keychain -p {keychain_pass} {keychain_name}")

    # decode and import cert to keychain
    print("Decoding certificate")
    cert_path = os.path.join("ci", "cert.p12")
    cert_pass = args.keychain_cert_pass
    with open(cert_path, "wb") as file:
        file.write(base64.decodebytes(args.keychain_cert.encode()))
    print("Importing certificate")
    # -A = allow access to the keychain without warning (https://stackoverflow.com/a/19550453)
    call(f"security import {cert_path} -k {keychain_name} -P {cert_pass} -A")
    os.remove(cert_path)

    # required since macOS Sierra https://stackoverflow.com/a/40039594
    call(
        f"security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k {keychain_pass} {keychain_name}"
    )
    # prevent the keychain from auto-locking
    call(f"security set-keychain-settings {keychain_name}")

    # add the keychain to the keychain search list
    call(f"security list-keychains -d user -s {keychain_name}")

    print("Done with keychain setup")

def get_github_token():
    return os.environ.get('SERVICES_GITHUB_TOKEN', None)

def setup_windows_cert(args):
    print("Setting up certificate")
    cert_path = os.path.abspath(os.path.join("ci", "windows_cert.pfx"))
    with open(cert_path, "wb") as file:
        file.write(base64.decodebytes(args.windows_cert_b64.encode()))
    print("Wrote cert to", cert_path)
    cert_pass_path = os.path.abspath(os.path.join("ci", "windows_cert.pass"))
    with open(cert_pass_path, "wb") as file:
        file.write(args.windows_cert_pass.encode())
    print("Wrote cert password to", cert_pass_path)


def install(args):
    # installed tools: https://github.com/actions/virtual-environments/blob/main/images/linux/Ubuntu2004-Readme.md
    system = platform.system()
    print(f"Installing dependencies for system '{system}' ")
    if system == "Linux":
        # we use apt-fast to speed up apt-get downloads
        # https://github.com/ilikenwf/apt-fast
        call("sudo add-apt-repository ppa:apt-fast/stable")
        call("sudo apt-get update", failonerror=False)
        call("echo debconf apt-fast/maxdownloads string 16 | sudo debconf-set-selections")
        call("echo debconf apt-fast/dlflag boolean true | sudo debconf-set-selections")
        call("echo debconf apt-fast/aptmanager string apt-get | sudo debconf-set-selections")
        call("sudo apt-get install -y apt-fast aria2")

        call("sudo apt-get install -y software-properties-common")

        call("ls /usr/bin/clang*")

        call("sudo update-alternatives --remove-all clang")
        call("sudo update-alternatives --remove-all clang++")
        call("sudo update-alternatives --install /usr/bin/clang clang /usr/bin/clang-12 120 --slave /usr/bin/clang++ clang++ /usr/bin/clang++-12")

        packages = [
            "libssl-dev",
            "openssl",
            "libtool",
            "autoconf",
            "automake",
            "build-essential",
            "uuid-dev",
            "libxi-dev",
            "libopenal-dev",
            "libgl1-mesa-dev",
            "libglw1-mesa-dev",
            "freeglut3-dev",
            "tofrodos",
            "tree",
            "valgrind",
            "lib32z1",
            "xvfb"
        ]
        aptfast(" ".join(packages))

    elif system == "Darwin":
        if args.keychain_cert:
            setup_keychain(args)
    elif system == "Windows":
        if args.windows_cert_b64:
            setup_windows_cert(args)


def build_engine(platform, channel, with_valgrind = False, with_asan = False, with_ubsan = False, with_tsan = False,
                with_vanilla_lua = False, skip_tests = False, skip_build_tests = False, skip_codesign = True,
                skip_docs = False, skip_builtins = False, archive = False):

    install_sdk = ''
    if platform not in (
        'x86_64-macos',
        'arm64-macos',
        'arm64-ios',
        'x86_64-ios',
    ):
        install_sdk = 'install_sdk'

    args = f'python scripts/build.py distclean {install_sdk} install_ext'.split()

    waf_opts = []

    opts = [f'--platform={platform}']
    if platform in ['js-web', 'wasm-web']:
        args.append('install_ems')

    args.append('build_engine')

    if channel:
        opts.append(f'--channel={channel}')

    if archive:
        args.append('archive_engine')

    if skip_codesign:
        opts.append('--skip-codesign')
    if skip_docs:
        opts.append('--skip-docs')
    if skip_builtins:
        opts.append('--skip-builtins')
    if skip_tests:
        opts.append('--skip-tests')
    if skip_build_tests:
        waf_opts.append('--skip-build-tests')

    if with_valgrind:
        waf_opts.append('--with-valgrind')
    if with_asan:
        waf_opts.append('--with-asan')
    if with_ubsan:
        waf_opts.append('--with-ubsan')
    if with_tsan:
        waf_opts.append('--with-tsan')
    if with_vanilla_lua:
        waf_opts.append('--use-vanilla-lua')

    cmd = ' '.join(args + opts)

    # Add arguments to waf after a double-dash
    if waf_opts:
        cmd += ' -- ' + ' '.join(waf_opts)

    call(cmd)


def build_editor2(channel, engine_artifacts = None, skip_tests = False):
    host_platform = platform_from_host()
    if host_platform not in PLATFORMS_DESKTOP:
        return

    opts = []

    if engine_artifacts:
        opts.append(f'--engine-artifacts={engine_artifacts}')

    opts.append(f'--channel={channel}')

    if skip_tests:
        opts.append('--skip-tests')

    opts_string = ' '.join(opts)

    call(
        f'python scripts/build.py distclean install_ext build_editor2 --platform={host_platform} {opts_string}'
    )
    for platform in PLATFORMS_DESKTOP:
        call(
            f'python scripts/build.py bundle_editor2 --platform={platform} {opts_string}'
        )

def download_editor2(channel, platform = None):
    host_platform = platform_from_host()
    platforms = PLATFORMS_DESKTOP if platform is None else [platform]
    opts = [f'--channel={channel}']
    install_sdk = 'install_sdk' if 'win32' in host_platform else ''
    for platform in platforms:
        call(
            f"python scripts/build.py {install_sdk} install_ext download_editor2 --platform={platform} {' '.join(opts)}"
        )


def sign_editor2(platform, windows_cert = None, windows_cert_pass = None):
    args = 'python scripts/build.py sign_editor2'.split()
    opts = [f'--platform={platform}']

    if windows_cert:
        windows_cert = os.path.abspath(windows_cert)
        if not os.path.exists(windows_cert):
            print("Certificate file not found:", windows_cert)
            sys.exit(1)
        print("Using cert", windows_cert)
        opts.append(f'--windows-cert={windows_cert}')

    if windows_cert_pass:
        windows_cert_pass = os.path.abspath(windows_cert_pass)
        opts.append(f"--windows-cert-pass={windows_cert_pass}")

    cmd = ' '.join(args + opts)
    call(cmd)


def notarize_editor2(notarization_username = None, notarization_password = None, notarization_itc_provider = None):
    if not notarization_username or not notarization_password:
        print("No notarization username or password")
        exit(1)

    # args = 'python scripts/build.py download_editor2 notarize_editor2 archive_editor2'.split()
    args = 'python scripts/build.py notarize_editor2'.split()
    opts = [
        '--platform=x86_64-macos',
        f'--notarization-username="{notarization_username}"',
        f'--notarization-password="{notarization_password}"',
    ]

    if notarization_itc_provider:
        opts.append(f'--notarization-itc-provider="{notarization_itc_provider}"')

    cmd = ' '.join(args + opts)
    call(cmd)


def archive_editor2(channel, engine_artifacts = None, platform = None):
    platforms = PLATFORMS_DESKTOP if platform is None else [platform]
    opts = [f"--channel={channel}"]
    if engine_artifacts:
        opts.append(f'--engine-artifacts={engine_artifacts}')

    opts_string = ' '.join(opts)
    for platform in platforms:
        call(
            f'python scripts/build.py install_ext archive_editor2 --platform={platform} {opts_string}'
        )

def distclean():
    call("python scripts/build.py distclean")


def install_ext(platform = None):
    opts = []
    if platform:
        opts.append(f'--platform={platform}')

    call(f"python scripts/build.py install_ext {' '.join(opts)}")

def build_bob(channel, branch = None):
    args = "python scripts/build.py install_ext sync_archive build_bob archive_bob".split()
    opts = [f"--channel={channel}"]
    cmd = ' '.join(args + opts)
    call(cmd)


def release(channel):
    args = "python scripts/build.py install_ext release".split()
    opts = [f"--channel={channel}"]
    if token := get_github_token():
        opts.append(f"--github-token={token}")

    cmd = ' '.join(args + opts)
    call(cmd)

def build_sdk(channel):
    args = "python scripts/build.py install_ext build_sdk".split()
    opts = [f"--channel={channel}"]
    cmd = ' '.join(args + opts)
    call(cmd)


def smoke_test():
    call('python scripts/build.py distclean install_ext smoke_test')



def get_branch():
    # The name of the head branch. Only set for pull request events.
    branch = os.environ.get('GITHUB_HEAD_REF', '')
    if branch == '':
        # The branch or tag name that triggered the workflow run.
        branch = os.environ.get('GITHUB_REF_NAME', '')

    if branch == '':
        # https://stackoverflow.com/a/55276236/1266551
        branch = call("git rev-parse --abbrev-ref HEAD").strip()
        if branch == "HEAD":
            branch = call("git rev-parse HEAD")

    return branch

def get_pull_request_target_branch():
    # The name of the base (or target) branch. Only set for pull request events.
    return os.environ.get('GITHUB_BASE_REF', '')

def is_workflow_enabled_in_repo():
    if not is_repo_private():
        return True # all workflows are enabled by default

    workflow = os.environ.get('GITHUB_WORKFLOW', '')
    return workflow in ('CI - Main',)

def main(argv):
    if not is_workflow_enabled_in_repo():
        print(
            f"Workflow '{os.environ.get('GITHUB_WORKFLOW', '')}' is disabled in repo '{os.environ.get('GITHUB_REPOSITORY', '')}'. Skipping"
        )
        return

    parser = ArgumentParser()
    parser.add_argument('commands', nargs="+", help="The command to execute (engine, build-editor, notarize-editor, archive-editor, bob, sdk, install, smoke)")
    parser.add_argument("--platform", dest="platform", help="Platform to build for (when building the engine)")
    parser.add_argument("--with-asan", dest="with_asan", action='store_true', help="")
    parser.add_argument("--with-ubsan", dest="with_ubsan", action='store_true', help="")
    parser.add_argument("--with-tsan", dest="with_tsan", action='store_true', help="")
    parser.add_argument("--with-valgrind", dest="with_valgrind", action='store_true', help="")
    parser.add_argument("--with-vanilla-lua", dest="with_vanilla_lua", action='store_true', help="")
    parser.add_argument("--archive", dest="archive", action='store_true', help="Archive engine artifacts to S3")
    parser.add_argument("--skip-tests", dest="skip_tests", action='store_true', help="")
    parser.add_argument("--skip-build-tests", dest="skip_build_tests", action='store_true', help="")
    parser.add_argument("--skip-builtins", dest="skip_builtins", action='store_true', help="")
    parser.add_argument("--skip-docs", dest="skip_docs", action='store_true', help="")
    parser.add_argument("--engine-artifacts", dest="engine_artifacts", help="Engine artifacts to include when building the editor")
    parser.add_argument("--keychain-cert", dest="keychain_cert", help="Base 64 encoded certificate to import to macOS keychain")
    parser.add_argument("--keychain-cert-pass", dest="keychain_cert_pass", help="Password for the certificate to import to macOS keychain")
    parser.add_argument("--windows-cert-b64", dest="windows_cert_b64", help="String containing Windows certificate (pfx) encoded as base 64")
    parser.add_argument("--windows-cert", dest="windows_cert", help="File containing Windows certificate (pfx)")
    parser.add_argument("--windows-cert-pass", dest="windows_cert_pass", help="File containing password for the Windows certificate")
    parser.add_argument('--notarization-username', dest='notarization_username', help="Username to use when sending the editor for notarization")
    parser.add_argument('--notarization-password', dest='notarization_password', help="Password to use when sending the editor for notarization")
    parser.add_argument('--notarization-itc-provider', dest='notarization_itc_provider', help="Optional iTunes Connect provider to use when sending the editor for notarization")
    parser.add_argument('--github-token', dest='github_token', help='GitHub authentication token when releasing to GitHub')
    parser.add_argument('--github-target-repo', dest='github_target_repo', help='GitHub target repo when releasing artefacts')
    parser.add_argument('--github-sha1', dest='github_sha1', help='A specific sha1 to use in github operations')

    args = parser.parse_args()

    platform = args.platform

    if platform and not is_platform_supported(platform):
        print(
            f"Platform {platform} is private and the repo '{os.environ.get('GITHUB_REPOSITORY', '')}' cannot build for this platform. Skipping"
        )
        return;

    # saving lots of CI minutes and waiting by not building the editor, which we don't use
    if is_repo_private():
        for command in args.commands:
            if 'editor' in command:
                print(
                    f"Platform {platform} is private we've disabled building the editor. Skipping"
                )
                return

    branch = get_branch()

    # configure build flags based on the branch
    release_channel = None
    skip_editor_tests = False
    make_release = False
    if branch == "master":
        engine_channel = "stable"
        editor_channel = "editor-alpha"
        release_channel = "stable"
        make_release = True
        engine_artifacts = args.engine_artifacts or "archived"
    elif branch == "beta":
        engine_channel = "beta"
        editor_channel = "beta"
        release_channel = "beta"
        make_release = True
        engine_artifacts = args.engine_artifacts or "archived"
    elif branch == "dev":
        engine_channel = "alpha"
        editor_channel = "alpha"
        release_channel = "alpha"
        make_release = True
        engine_artifacts = args.engine_artifacts or "archived"
    elif branch == "editor-dev":
        engine_channel = None
        editor_channel = "editor-alpha"
        release_channel = "editor-alpha"
        make_release = True
        engine_artifacts = args.engine_artifacts
    elif branch and (branch.startswith("DEFEDIT-") or get_pull_request_target_branch() == "editor-dev"):
        engine_channel = None
        editor_channel = "editor-dev"
        engine_artifacts = args.engine_artifacts or "archived-stable"
    else: # engine dev branch
        engine_channel = "dev"
        editor_channel = "dev"
        engine_artifacts = args.engine_artifacts or "archived"

    print(
        f"Using branch={branch} engine_channel={engine_channel} editor_channel={editor_channel} engine_artifacts={engine_artifacts}"
    )

    # execute commands
    for command in args.commands:
        if command == "archive-editor":
            archive_editor2(editor_channel, engine_artifacts = engine_artifacts, platform = platform)
        elif command == "bob":
            build_bob(engine_channel, branch = branch)
        elif command == "build-editor":
            build_editor2(editor_channel, engine_artifacts = engine_artifacts, skip_tests = skip_editor_tests)
        elif command == "distclean":
            distclean()
        elif command == "download-editor":
            download_editor2(editor_channel, platform = platform)
        elif command == "engine":
            if not platform:
                raise Exception("No --platform specified.")
            build_engine(
                platform,
                engine_channel,
                with_valgrind = args.with_valgrind or (branch in [ "master", "beta" ]),
                with_asan = args.with_asan,
                with_ubsan = args.with_ubsan,
                with_tsan = args.with_tsan,
                with_vanilla_lua = args.with_vanilla_lua,
                archive = args.archive,
                skip_tests = args.skip_tests,
                skip_build_tests = args.skip_build_tests,
                skip_builtins = args.skip_builtins,
                skip_docs = args.skip_docs)
        elif command == "install":
            install(args)
        elif command == "install_ext":
            install_ext(platform = platform)
        elif command == "notarize-editor":
            notarize_editor2(
                notarization_username = args.notarization_username,
                notarization_password = args.notarization_password,
                notarization_itc_provider = args.notarization_itc_provider)
        elif command == "release":
            if make_release:
                release(release_channel)
            else:
                print(f"Branch '{branch}' is not configured for automatic release from CI")
        elif command == "sdk":
            build_sdk(engine_channel)
        elif command == "sign-editor":
            if not platform:
                raise Exception("No --platform specified.")
            sign_editor2(platform, windows_cert = args.windows_cert, windows_cert_pass = args.windows_cert_pass)
        elif command == "smoke":
            smoke_test()
        else:
            print("Unknown command {0}".format(command))


if __name__ == "__main__":
    main(sys.argv[1:])
