#!/usr/bin/env bash
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

. ./version.sh

readonly BASE_URL=https://github.com/LuaJIT/LuaJIT/archive/
readonly FILE_URL=${SHA1}.zip

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"


# Whether to skip including /bin/ in the package, since it's only needed for desktop platforms
TAR_SKIP_BIN=0

function luajit_configure() {
	export MAKEFLAGS="-e -j8"
	export BUILDMODE=static
	export PREFIX=`pwd`/build
	export INSTALL_LIB=$PREFIX/lib/$CONF_TARGET
	export INSTALL_BIN=$PREFIX/bin/$CONF_TARGET

	# These are where the .lua files go. Do not want them in default
	# folder as that includes the version, which complicates things
	# in bob, where LUAJIT_PATH must be matched to the location of
	# these files.
	export INSTALL_LJLIBD=$PREFIX/share/luajit

	# Some architectures (e.g. PPC) can use either single-number (1) or
	# dual-number (2) mode. For clarity we explicitly use LUAJIT_NUMMODE=2
	# for mobile architectures.

	COMMON_XCFLAGS="-DLUA_USERDATA_ALIGNMENT=16 "

	COMMON_FLAGS_32="-DLUAJIT_DISABLE_JIT -DLUAJIT_NUMMODE=LJ_NUMMODE_DUAL -DLUAJIT_DISABLE_GC64 "
	COMMON_FLAGS_64="-DLUAJIT_DISABLE_JIT -DLUAJIT_NUMMODE=LJ_NUMMODE_DUAL "

	case $CONF_TARGET in
		arm64-ios)
			TAR_SKIP_BIN=1
			XFLAGS="$COMMON_FLAGS_64"
			export CROSS=""
			export PATH=$DARWIN_TOOLCHAIN_ROOT/usr/bin:$PATH
			export HOST_CC="$DARWIN_TOOLCHAIN_ROOT/usr/bin/clang"
			export HOST_CFLAGS="$XFLAGS -m64 -isysroot $OSX_SDK_ROOT -I."
			export HOST_ALDFLAGS="-m64 -isysroot $OSX_SDK_ROOT"
			export TARGET_FLAGS="$CFLAGS"
			export XCFLAGS="-DLUAJIT_TARGET=LUAJIT_ARCH_ARM64 ${COMMON_XCFLAGS}"
			;;
		x86_64-ios)
			TAR_SKIP_BIN=1
			XFLAGS="$COMMON_FLAGS_64"
			export PATH=$DARWIN_TOOLCHAIN_ROOT/usr/bin:$PATH
			export HOST_CC="$DARWIN_TOOLCHAIN_ROOT/usr/bin/clang"
			export HOST_CFLAGS="$XFLAGS -m64 -isysroot $OSX_SDK_ROOT -I."
			export HOST_ALDFLAGS="-m64 -isysroot $OSX_SDK_ROOT"
			export TARGET_FLAGS="$CFLAGS"
			export XCFLAGS="${COMMON_XCFLAGS}"
			;;
		armv7-android)
			TAR_SKIP_BIN=1
			XFLAGS="$COMMON_FLAGS_32"
			export CROSS=""
			export HOST_CC="clang"
			export HOST_CFLAGS="$XFLAGS -m32 -I."
			export HOST_ALDFLAGS="-m32"
			export XCFLAGS="-DLUAJIT_TARGET=LUAJIT_ARCH_ARM ${COMMON_XCFLAGS}"
			;;
		arm64-android)
			TAR_SKIP_BIN=1
			XFLAGS="$COMMON_FLAGS_64"
			export CROSS=""
			export HOST_CC="clang"
			export HOST_CFLAGS="$XFLAGS -m64 -I."
			export HOST_ALDFLAGS="-m64"
			export TARGET_FLAGS="$CFLAGS"
			export XCFLAGS="-DLUAJIT_TARGET=LUAJIT_ARCH_ARM64 ${COMMON_XCFLAGS}"
			;;
		*)
			return
			;;
	esac

	# Time to second guess the luajit make file trickeries
	# for cross-compiles!

	# We pass in cross compilation set up in CFLAGS/etc. We need to
	# transfer those to TARGET* flags and then restore HOST* values
	# to what is used to not cross-compile.
	#
	# Host need to generate same pointer size as target, though.
	# Which means we do -m32 for that.

	# These will be used for the cross compiling
	export TARGET_TCFLAGS="$CFLAGS $XFLAGS"
	export TARGET_CFLAGS="$CFLAGS $XFLAGS"

	# These are used for host compiling
	export HOST_XCFLAGS=""
	export HOST_LDFLAGS="${BUILD_LDFLAGS}"

	# Disable
	export TARGET_STRIP=true
	export CCOPTIONS=
}

# Use above function instead of shell scripts
CONFIGURE_WRAPPER="luajit_configure"
export -f luajit_configure

. ../common.sh


function cmi_unpack() {
	echo cmi_unpack
	# simulate the "--strip-components=1" of the tar unpack command
	local dest=$(pwd)
	local temp=$(mktemp -d)
	unzip -q -d "$temp" $SCRIPTDIR/download/$FILE_URL
	mv "$temp"/*/* "$dest"
	rm -rf "$temp"
}

function cmi_patch() {
    echo cmi_patch
    if [ -f ../patch_$VERSION ]; then
        echo "Applying patch ../patch_$VERSION" && patch -l --binary -p1 < ../patch_$VERSION
    fi
}


export CONF_TARGET=$1

case $1 in
	arm64-ios)
		export TARGET_SYS=iOS
		;;
	x86_64-ios)
		export TARGET_SYS=iOS
		;;
	armv7-android)
		# using insttructions from here: file:///Users/mawe/work/defold-ps4/share/ext/luajit/tmp/doc/install.html
		export TARGET_SYS=Android
		function cmi_make() {
			local host_platform=`uname | awk '{print tolower($0)}'`
			export NDKBIN=${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${host_platform}-x86_64/bin
			NDKCROSS=$NDKBIN/arm-linux-androideabi-
			make -j8 CROSS=$NDKCROSS \
					STATIC_CC=${CC} DYNAMIC_CC="${CC} ${CFLAGS}" \
					TARGET_LD=${CC} TARGET_AR="${AR} rcus" \
					TARGET_STRIP=$NDKBIN/llvm-strip
			make install
		}
		;;
	arm64-android)
		# using insttructions from here: file:///Users/mawe/work/defold-ps4/share/ext/luajit/tmp/doc/install.html
		export TARGET_SYS=Android
		function cmi_make() {
			local host_platform=`uname | awk '{print tolower($0)}'`
			export NDKBIN=${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${host_platform}-x86_64/bin
			export NDKCROSS=$NDKBIN/aarch64-linux-android-
			make -j8 CROSS=$NDKCROSS \
					STATIC_CC=${CC} DYNAMIC_CC="${CC} ${CFLAGS}" \
					TARGET_LD=${CC} TARGET_AR="${AR} rcus" \
					TARGET_STRIP=$NDKBIN/llvm-strip
			make install
		}
		;;
	x86_64-linux)
		export TARGET_SYS=Linux
		function cmi_make() {
					export DEFOLD_ARCH="32"
					export XCFLAGS="-DLUAJIT_DISABLE_GC64 ${COMMON_XCFLAGS}"

					export HOST_CC="clang"
					export HOST_CFLAGS="${COMMON_XCFLAGS} -I."
					export HOST_ALDFLAGS=""
					export TARGET_LDFLAGS=""

					echo "Building $CONF_TARGET ($DEFOLD_ARCH) with '$XCFLAGS'"
					set -e
					make -j8
					make install
					mv $PREFIX/bin/$CONF_TARGET/${TARGET_FILE} $PREFIX/bin/$CONF_TARGET/luajit-${DEFOLD_ARCH}
					make clean
					set +e

					export DEFOLD_ARCH="64"
					export XCFLAGS=" ${COMMON_XCFLAGS}"

					export HOST_CC="clang"
					export HOST_CFLAGS="${COMMON_XCFLAGS} -m64 -I."
					export HOST_ALDFLAGS="-m64"
					export TARGET_LDFLAGS="-m64"

					echo "Building $CONF_TARGET ($DEFOLD_ARCH) with '$XCFLAGS'"
					set -e
					make -j8
					make install
					mv $PREFIX/bin/$CONF_TARGET/${TARGET_FILE} $PREFIX/bin/$CONF_TARGET/luajit-${DEFOLD_ARCH}
					set +e
		}
		;;
	x86_64-macos)
		export TARGET_SYS=Darwin
		function cmi_make() {
			export MACOSX_DEPLOYMENT_TARGET=${OSX_MIN_SDK_VERSION}

			# Since GC32 mode isn't supported on macOS, in the new version.
			# We'll just use the old built executable from the previous package
			# (we need the GC32 for generating 32 bit Lua source for 32 bit platforms: win32, armv7-android)

			export DEFOLD_ARCH="64"
			export TARGET_CFLAGS=""
			export XCFLAGS="${COMMON_XCFLAGS}"
			echo "Building $CONF_TARGET ($DEFOLD_ARCH) with '$TARGET_CFLAGS'"
			set -e
			make -j1
			make install
			mv $PREFIX/bin/$CONF_TARGET/${TARGET_FILE} $PREFIX/bin/$CONF_TARGET/luajit-${DEFOLD_ARCH}
			set +e

			# grab our old 32 bit executable and store it in the host package
			set -e
			tar xvf ${DIR}/luajit-2.1.0-beta3-x86_64-macos.tar.gz
			cp -v bin/x86_64-macos/luajit-32 $PREFIX/bin/$CONF_TARGET/luajit-32
			set +e
		}
		;;
	arm64-macos)
		export TARGET_SYS=Darwin
		function cmi_make() {
			export MACOSX_DEPLOYMENT_TARGET=${OSX_MIN_SDK_VERSION}
			export DEFOLD_ARCH="64"
			export TARGET_CFLAGS=""
			export XCFLAGS="${COMMON_XCFLAGS}"
			echo "Building $CONF_TARGET ($DEFOLD_ARCH) with '$TARGET_CFLAGS'"
			set -e
			arch -arm64 make -j8
			arch -arm64 make install
			mv $PREFIX/bin/$CONF_TARGET/${TARGET_FILE} $PREFIX/bin/$CONF_TARGET/luajit-${DEFOLD_ARCH}
			set +e
		}
		;;
esac

download
cmi $1
