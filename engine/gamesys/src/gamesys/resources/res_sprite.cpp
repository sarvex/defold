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

#include "res_sprite.h"

#include <string.h>

#include <dlib/log.h>

#include <gamesys/sprite_ddf.h>

namespace dmGameSystem
{
    dmResource::Result AcquireResources(dmResource::HFactory factory,
        SpriteResource* resource, const char* filename)
    {
        // Add-alpha is deprecated because of premultiplied alpha and replaced by Add
        if (resource->m_DDF->m_BlendMode == dmGameSystemDDF::SpriteDesc::BLEND_MODE_ADD_ALPHA)
        {
            resource->m_DDF->m_BlendMode = dmGameSystemDDF::SpriteDesc::BLEND_MODE_ADD;
        }

        TextureSetResource* texture_sets[dmRender::RenderObject::MAX_TEXTURE_COUNT] = {0};

        dmResource::Result fr = dmResource::RESULT_OK;

        for (int i = 0; i < resource->m_DDF->m_TileSet.m_Count; ++i)
        {
            fr = dmResource::Get(factory, resource->m_DDF->m_TileSet[i], (void**) &texture_sets[i]); // (void**)&resource->m_TextureSet);
            if (fr != dmResource::RESULT_OK)
            {
                return fr;
            }
        }

        memcpy(resource->m_TextureSet, texture_sets, sizeof(texture_sets));

        fr = dmResource::Get(factory, resource->m_DDF->m_Material, (void**)&resource->m_Material);
        if (fr != dmResource::RESULT_OK)
        {
            return fr;
        }
        if(dmRender::GetMaterialVertexSpace(resource->m_Material) != dmRenderDDF::MaterialDesc::VERTEX_SPACE_WORLD)
        {
            dmLogError("Failed to create Sprite component. This component only supports materials with the Vertex Space property set to 'vertex-space-world'");
            return dmResource::RESULT_NOT_SUPPORTED;
        }

        for (int i = 0; i < resource->m_DDF->m_DefaultAnimation.m_Count; ++i)
        {
            resource->m_DefaultAnimation[i] = dmHashString64(resource->m_DDF->m_DefaultAnimation[i]);

            if (!resource->m_TextureSet[i]->m_AnimationIds.Get(resource->m_DefaultAnimation[i]))
            {
                if (resource->m_DDF->m_DefaultAnimation[i] == 0 || resource->m_DDF->m_DefaultAnimation[i][0] == '\0')
                {
                    dmLogError("No default animation specified");
                }
                else
                {
                    dmLogError("Default animation '%s' not found", resource->m_DDF->m_DefaultAnimation[i]);
                }
                return dmResource::RESULT_FORMAT_ERROR;;
            }
        }

        return dmResource::RESULT_OK;
    }

    void ReleaseResources(dmResource::HFactory factory, SpriteResource* resource)
    {
        if (resource->m_DDF != 0x0)
            dmDDF::FreeMessage(resource->m_DDF);

        for (int i = 0; i < dmRender::RenderObject::MAX_TEXTURE_COUNT; ++i)
        {
            if (resource->m_TextureSet[i] != 0x0)
            {
                dmResource::Release(factory, resource->m_TextureSet[i]);
            }
        }
        if (resource->m_Material != 0x0)
            dmResource::Release(factory, resource->m_Material);
    }

    dmResource::Result ResSpritePreload(const dmResource::ResourcePreloadParams& params)
    {
        dmGameSystemDDF::SpriteDesc *ddf;
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &ddf);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        for (int i = 0; i < ddf->m_TileSet.m_Count; ++i)
        {
            dmResource::PreloadHint(params.m_HintInfo, ddf->m_TileSet[i]);
        }

        dmResource::PreloadHint(params.m_HintInfo, ddf->m_Material);

        *params.m_PreloadData = ddf;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResSpriteCreate(const dmResource::ResourceCreateParams& params)
    {
        SpriteResource* sprite_resource = new SpriteResource();
        memset(sprite_resource, 0, sizeof(SpriteResource));
        sprite_resource->m_DDF = (dmGameSystemDDF::SpriteDesc*) params.m_PreloadData;

        dmResource::Result r = AcquireResources(params.m_Factory, sprite_resource, params.m_Filename);
        if (r == dmResource::RESULT_OK)
        {
            params.m_Resource->m_Resource = (void*) sprite_resource;
        }
        else
        {
            ReleaseResources(params.m_Factory, sprite_resource);
            delete sprite_resource;
        }
        return r;
    }

    dmResource::Result ResSpriteDestroy(const dmResource::ResourceDestroyParams& params)
    {
        SpriteResource* sprite_resource = (SpriteResource*) params.m_Resource->m_Resource;
        ReleaseResources(params.m_Factory, sprite_resource);
        delete sprite_resource;
        return dmResource::RESULT_OK;
    }

    dmResource::Result ResSpriteRecreate(const dmResource::ResourceRecreateParams& params)
    {
        SpriteResource tmp_sprite_resource;
        memset(&tmp_sprite_resource, 0, sizeof(SpriteResource));
        dmDDF::Result e = dmDDF::LoadMessage(params.m_Buffer, params.m_BufferSize, &tmp_sprite_resource.m_DDF);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::RESULT_FORMAT_ERROR;
        }

        dmResource::Result r = AcquireResources(params.m_Factory, &tmp_sprite_resource, params.m_Filename);
        if (r == dmResource::RESULT_OK)
        {
            SpriteResource* sprite_resource = (SpriteResource*)params.m_Resource->m_Resource;
            ReleaseResources(params.m_Factory, sprite_resource);
            *sprite_resource = tmp_sprite_resource;
        }
        else
        {
            ReleaseResources(params.m_Factory, &tmp_sprite_resource);
        }
        return r;
    }
}
