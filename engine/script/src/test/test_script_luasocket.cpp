// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>

#include <stdio.h>
#include <stdint.h>

#include "script.h"
#include "script_vmath.h"

#include <dlib/log.h>
#include <dlib/dstrings.h>

extern "C"
{
#include <lua/lauxlib.h>
#include <lua/lualib.h>
}

#define PATH_FORMAT "build/src/test/%s"

class ScriptLuasocketTest : public jc_test_base_class
{
protected:
    virtual void SetUp()
    {
        m_Context = dmScript::NewContext(0, 0, true);
        dmScript::Initialize(m_Context);
        L = dmScript::GetLuaState(m_Context);
    }

    virtual void TearDown()
    {
        dmScript::Finalize(m_Context);
        dmScript::DeleteContext(m_Context);
    }

    dmScript::HContext m_Context;
    lua_State* L;
};

bool RunFile(lua_State* L, const char* filename)
{
    char path[64];
    dmSnPrintf(path, 64, PATH_FORMAT, filename);
    if (luaL_dofile(L, path) != 0)
    {
        dmLogError("%s", lua_tolstring(L, -1, 0));
        return false;
    }
    return true;
}

TEST_F(ScriptLuasocketTest, TestLuasocket)
{
    int top = lua_gettop(L);

    ASSERT_TRUE(RunFile(L, "test_luasocket.luac"));

    const char *funcs[] = {
        "test_bind", "test_getaddr",
        "test_udp", "test_tcp_clientserver",
        "test_udp_clientserver", "test_bind_error", 0
    };

    for (int i=0;funcs[i];i++)
    {
        lua_getglobal(L, "functions");
        ASSERT_EQ(LUA_TTABLE, lua_type(L, -1));
        lua_getfield(L, -1, funcs[i]);
        ASSERT_EQ(LUA_TFUNCTION, lua_type(L, -1));
        int result = dmScript::PCall(L, 0, LUA_MULTRET);
        if (result == LUA_ERRRUN)
        {
            ASSERT_TRUE(false);
        }
        else
        {
            ASSERT_EQ(0, result);
        }
        lua_pop(L, 1);
	}

    ASSERT_EQ(top, lua_gettop(L));
}

int main(int argc, char **argv)
{
    jc_test_init(&argc, argv);

    int ret = jc_test_run_all();
    return ret;
}
