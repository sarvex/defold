#include <gtest/gtest.h>

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

#define PATH_FORMAT "build/default/src/test/%s"

class ScriptLuasocketTest : public ::testing::Test
{
protected:
    virtual void SetUp()
    {
        m_Context = dmScript::NewContext(0, 0);
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
    DM_SNPRINTF(path, 64, PATH_FORMAT, filename);
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
        "test_udp", 0
    };

    for (int i=0;funcs[i];i++)
    {
        lua_getglobal(L, "functions");
        ASSERT_EQ(LUA_TTABLE, lua_type(L, -1));
        lua_getfield(L, -1, funcs[i]);
        ASSERT_EQ(LUA_TFUNCTION, lua_type(L, -1));
        int result = lua_pcall(L, 0, LUA_MULTRET, 0);
        if (result == LUA_ERRRUN)
        {
            dmLogError("Error running function %s in script: %s", funcs[i], lua_tostring(L,-1));
            ASSERT_TRUE(false);
           lua_pop(L, 1);
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
    testing::InitGoogleTest(&argc, argv);

    int ret = RUN_ALL_TESTS();
    return ret;
}
