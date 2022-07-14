// Copyright 2020-2022 The Defold Foundation
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

package com.dynamo.bob.pipeline;

import java.io.IOException;
import java.util.List;

import com.dynamo.bob.Bob;
import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Project;
import com.dynamo.bob.Task;
import com.dynamo.bob.Task.TaskBuilder;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.bob.util.TextureUtil;
import com.dynamo.graphics.proto.Graphics.TextureImage;
import com.dynamo.graphics.proto.Graphics.TextureProfile;
import com.dynamo.gamesys.proto.TextureSetProto.TextureSet;
import com.dynamo.gamesys.proto.TextureSetProto.TexturePageOffsets;
import com.dynamo.gamesys.proto.AtlasProto.Atlas;
import com.dynamo.gamesys.proto.AtlasProto.AtlasImage;
import com.dynamo.gamesys.proto.AtlasProto.AtlasPage;

@BuilderParams(name = "Atlas", inExts = {".atlas"}, outExt = ".a.texturesetc")
public class AtlasBuilder extends Builder<Void>  {

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        Atlas.Builder builder = Atlas.newBuilder();
        ProtoUtil.merge(input, builder);
        Atlas atlas = builder.build();

        TaskBuilder<Void> taskBuilder = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()))
                .addOutput(input.changeExt(".texturec"));

        for (AtlasImage image : AtlasUtil.collectImages(atlas)) {
            taskBuilder.addInput(input.getResource(image.getImage()));
        }

        // If there is a texture profiles file, we need to make sure
        // it has been read before building this tile set, add it as an input.
        String textureProfilesPath = this.project.getProjectProperties().getStringValue("graphics", "texture_profiles");
        if (textureProfilesPath != null) {
            taskBuilder.addInput(this.project.getResource(textureProfilesPath));
        }

        int page_index = 0;
        for (AtlasPage page : atlas.getPagesList()) {
            String page_name = ".page_" + (page_index++);
            taskBuilder.addOutput(input.changeExt(page_name + ".texturec"));
        }

        return taskBuilder.build();
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {
        List<TextureSetResult> result = AtlasUtil.generateTextureSet(project, task.input(0));

        int buildDirLen = project.getBuildDirectory().length();
        String texturePath = task.output(1).getPath().substring(buildDirLen);
        TextureSet.Builder textureSetBuilder = result.get(0).builder;

        textureSetBuilder.addTextures(texturePath);

        TextureProfile texProfile = TextureUtil.getTextureProfileByPath(this.project.getTextureProfiles(), task.input(0).getPath());
        Bob.verbose("Compiling %s using profile %s", task.input(0).getPath(), texProfile!=null?texProfile.getName():"<none>");

        boolean compressTexture = project.option("texture-compression", "false").equals("true");
        TextureImage texture;
        try {
            texture = TextureGenerator.generate(result.get(0).image, texProfile, compressTexture);
        } catch (TextureGeneratorException e) {
            throw new CompileExceptionError(task.input(0), -1, e.getMessage(), e);
        }

        List<IResource> outputs = task.getOutputs();
        for (int i = 2; i < outputs.size(); i++) {

            int textureSetIndex                   = i-1;
            IResource pageTextureOutput           = outputs.get(i);
            TextureSetResult pageTextureSetResult = result.get(textureSetIndex);
            String pageTexturePath                = task.output(i).getPath().substring(buildDirLen);
            TextureSet pageTextureSet             = pageTextureSetResult.builder.addTextures(pageTexturePath).build();

            TextureImage pageTexture;
            try {
                pageTexture = TextureGenerator.generate(pageTextureSetResult.image, texProfile, compressTexture);
            } catch (TextureGeneratorException e) {
                throw new CompileExceptionError(task.input(0), -1, e.getMessage(), e);
            }

            TexturePageOffsets texturePageOffsetsBuilder = TexturePageOffsets.newBuilder()
                .setConvexHulls(textureSetBuilder.getConvexHullsCount())
                .setCollisionHullPoints(textureSetBuilder.getCollisionHullPointsCount())
                .setCollisionGroups(textureSetBuilder.getCollisionGroupsCount())
                .setTexCoords(textureSetBuilder.getTexCoords().size())
                .setTexDims(textureSetBuilder.getTexDims().size())
                .setFrameIndices(textureSetBuilder.getFrameIndicesCount())
                .setGeometries(textureSetBuilder.getGeometriesCount())
                .build();

            pageTextureOutput.setContent(pageTexture.toByteArray());
            textureSetBuilder.addTextures(pageTexturePath);
            textureSetBuilder.addAllAnimations(pageTextureSet.getAnimationsList());
            textureSetBuilder.addAllConvexHulls(pageTextureSet.getConvexHullsList());
            textureSetBuilder.addAllCollisionGroups(pageTextureSet.getCollisionGroupsList());
            textureSetBuilder.setTexCoords(textureSetBuilder.getTexCoords().concat(pageTextureSet.getTexCoords()));
            textureSetBuilder.setTexDims(textureSetBuilder.getTexDims().concat(pageTextureSet.getTexDims()));
            textureSetBuilder.addAllGeometries(pageTextureSet.getGeometriesList());
            textureSetBuilder.addAllFrameIndices(pageTextureSet.getFrameIndicesList());
            textureSetBuilder.addPageOffsets(texturePageOffsetsBuilder);
        }

        TextureSet textureSet = textureSetBuilder.build();
        task.output(0).setContent(textureSet.toByteArray());
        task.output(1).setContent(texture.toByteArray());
    }

}
