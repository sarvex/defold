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

#include <dlib/dstrings.h>
#include <dlib/log.h>

#include <dmsdk/resource/resource.h>
#include "res_lua.h"
#include "gameobject_script.h"

namespace dmGameObject
{
    void PatchBytes(uint8_t* bytes, uint32_t bytes_size, const uint8_t* delta, uint32_t delta_size)
    {
        uint32_t i = 0;
        while (i < delta_size) {
            // get the index
            uint32_t index = delta[i++];
            if (bytes_size >= (1 << 24))
            {
                index += ((delta[i++]) << 8);
                index += ((delta[i++]) << 16);
                index += ((delta[i++]) << 24);
            }
            else if (bytes_size >= (1 << 16))
            {
                index += ((delta[i++]) << 8);
                index += ((delta[i++]) << 16);
            }
            else if (bytes_size >= (1 << 8))
            {
                index += ((delta[i++]) << 8);
            }

            // get the number of consecutive bytes of changes
            uint32_t count = delta[i++];
            while (count-- > 0)
            {
                // apply the delta to the bytes
                bytes[index++] = delta[i++];
            }
        }
    }

    void PatchLuaBytecode(dmLuaDDF::LuaSource *source)
    {
        // the application can have bytecode bundled in three different ways:
        //
        // OPTION 1
        // if the application was bundled for use on both 32 and 64 bit hardware
        // using the 'use-lua-bytecode-delta' option it will contain both the
        // 64-bit bytecode and a 32-bit delta

        // OPTION 2
        // if the application was bundled for use on both 32 and 64 bit hardware
        // without the 'use-lua-bytecode-delta' option it will contain both the
        // 32-bit bytecode and the 64-bit bytecode

        // OPTION 3
        // if the application was bundled for only 32 or 64 bit hardware it will
        // only contain bytecode for the appropriate CPU architecture

        // OPTION 1 - patch bytecode if running on 32 bit
        if (source->m_Delta.m_Count > 0)
        {
#if defined(DM_PLATFORM_32BIT)
            // get the 32-bit bytecode delta generated by bob
            uint8_t* delta = source->m_Delta.m_Data;
            uint32_t delta_size = source->m_Delta.m_Count;

            // get the 64-bit bytecode
            uint8_t* bc = source->m_Bytecode.m_Data;
            uint32_t bc_size = source->m_Bytecode.m_Count;

            // apply the delta to the 64-bit bytecode
            PatchBytes(bc, bc_size, delta, delta_size);
#endif
        }
        // OPTION 2 - use 32 or 64 bit bytecode
        else if (source->m_Bytecode.m_Count == 0)
        {
#if defined(DM_PLATFORM_32BIT)
            source->m_Bytecode.m_Data = source->m_Bytecode32.m_Data;
            source->m_Bytecode.m_Count = source->m_Bytecode32.m_Count;
#else
            source->m_Bytecode.m_Data = source->m_Bytecode64.m_Data;
            source->m_Bytecode.m_Count = source->m_Bytecode64.m_Count;
#endif
        }
        // OPTION 3 - use bytecode as-is
        else
        {
            // no-op
        }
    }


    static dmResource::Result ResLuaCreate(const dmResource::ResourceCreateParams& params)
    {
        dmLuaDDF::LuaModule* lua_module = 0;
        dmDDF::Result e = dmDDF::LoadMessage<dmLuaDDF::LuaModule>(params.m_Buffer, params.m_BufferSize, &lua_module);
        if ( e != dmDDF::RESULT_OK )
            return dmResource::RESULT_FORMAT_ERROR;

        PatchLuaBytecode(&lua_module->m_Source);

        LuaScript* lua_script = new LuaScript(lua_module);
        params.m_Resource->m_Resource = lua_script;
        params.m_Resource->m_ResourceSize = sizeof(LuaScript) + params.m_BufferSize - lua_script->m_LuaModule->m_Source.m_Script.m_Count;
        return dmResource::RESULT_OK;
    }

    static dmResource::Result ResLuaDestroy(const dmResource::ResourceDestroyParams& params)
    {
        LuaScript* script = (LuaScript*) params.m_Resource->m_Resource;
        dmDDF::FreeMessage(script->m_LuaModule);
        delete script;
        return dmResource::RESULT_OK;
    }

    static dmResource::Result ResLuaRecreate(const dmResource::ResourceRecreateParams& params)
    {
        dmLuaDDF::LuaModule* lua_module = 0;
        dmDDF::Result e = dmDDF::LoadMessage<dmLuaDDF::LuaModule>(params.m_Buffer, params.m_BufferSize, &lua_module);
        if ( e != dmDDF::RESULT_OK )
            return dmResource::RESULT_FORMAT_ERROR;

        PatchLuaBytecode(&lua_module->m_Source);

        ModuleContext* module_context = (ModuleContext*) params.m_Context;
        uint32_t context_count = module_context->m_ScriptContexts.Size();
        for (uint32_t i = 0; i < context_count; ++i) {
            dmScript::HContext script_context = module_context->m_ScriptContexts[i];
            dmScript::ReloadModule(script_context, &lua_module->m_Source, params.m_Resource->m_NameHash);
        }
        LuaScript* lua_script = (LuaScript*) params.m_Resource->m_Resource;
        params.m_Resource->m_ResourceSize = sizeof(LuaScript) + params.m_BufferSize - lua_script->m_LuaModule->m_Source.m_Script.m_Count;
        dmDDF::FreeMessage(lua_script->m_LuaModule);
        lua_script->m_LuaModule = lua_module;
        return dmResource::RESULT_OK;
    }

    static dmResource::Result RegisterResourceTypeLua(dmResource::ResourceTypeRegisterContext& ctx)
    {
        // The engine.cpp creates the contexts for our built in types.
        void** context = ctx.m_Contexts->Get(ctx.m_NameHash);
        assert(context);
        return dmResource::RegisterType(ctx.m_Factory,
                                           ctx.m_Name,
                                           *context,
                                           0,
                                           ResLuaCreate,
                                           0,
                                           ResLuaDestroy,
                                           ResLuaRecreate);
    }
}

DM_DECLARE_RESOURCE_TYPE(ResourceTypeLua, "luac", dmGameObject::RegisterResourceTypeLua, 0);
