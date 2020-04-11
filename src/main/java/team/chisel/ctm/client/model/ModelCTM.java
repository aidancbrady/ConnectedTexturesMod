package team.chisel.ctm.client.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.val;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.BlockModel;
import net.minecraft.client.renderer.model.BlockPartFace;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.IModelTransform;
import net.minecraft.client.renderer.model.IUnbakedModel;
import net.minecraft.client.renderer.model.ItemOverrideList;
import net.minecraft.client.renderer.model.Material;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.IModelConfiguration;
import team.chisel.ctm.api.model.IModelCTM;
import team.chisel.ctm.api.texture.ICTMTexture;
import team.chisel.ctm.api.util.TextureInfo;
import team.chisel.ctm.client.texture.IMetadataSectionCTM;
import team.chisel.ctm.client.texture.render.TextureNormal;
import team.chisel.ctm.client.texture.type.TextureTypeNormal;
import team.chisel.ctm.client.util.ResourceUtil;

public class ModelCTM implements IModelCTM {
    
    private final IUnbakedModel vanillamodel;
    private final @Nullable BlockModel modelinfo;

    // Populated from overrides data during construction
    private final Int2ObjectMap<JsonElement> overrides;
    protected final Int2ObjectMap<IMetadataSectionCTM> metaOverrides = new Int2ObjectArrayMap<>();
    
    // Populated during bake with real texture data
    protected Int2ObjectMap<TextureAtlasSprite> spriteOverrides;
    protected Map<Pair<Integer, String>, ICTMTexture<?>> textureOverrides;

    private final Collection<ResourceLocation> textureDependencies;
    
    private transient byte layers;

    private Map<ResourceLocation, ICTMTexture<?>> textures = new HashMap<>();
    
    public ModelCTM(IUnbakedModel modelinfo) {
        this.vanillamodel = modelinfo;
        this.modelinfo = null;
        this.overrides = new Int2ObjectOpenHashMap<>();
        this.textureDependencies = new HashSet<>();
    }
    
    public ModelCTM(BlockModel modelinfo, Int2ObjectMap<JsonElement> overrides) throws IOException {
    	this.vanillamodel = modelinfo;
    	this.modelinfo = modelinfo;
    	this.overrides = overrides;
        this.textureDependencies = new HashSet<>();
        for (Entry<Integer, JsonElement> e : this.overrides.entrySet()) {
            IMetadataSectionCTM meta = null;
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                ResourceLocation rl = new ResourceLocation(e.getValue().getAsString());
                meta = ResourceUtil.getMetadata(ResourceUtil.spriteToAbsolute(rl));
                textureDependencies.add(rl);
            } else if (e.getValue().isJsonObject()) {
                JsonObject obj = e.getValue().getAsJsonObject();
                if (!obj.has("ctm_version")) {
                    // This model can only be version 1, TODO improve this
                    obj.add("ctm_version", new JsonPrimitive(1));
                }
                meta = new IMetadataSectionCTM.Serializer().deserialize(obj);
            }
            if (meta != null ) {
                metaOverrides.put(e.getKey(), meta);
                textureDependencies.addAll(Arrays.asList(meta.getAdditionalTextures()));
            }
        }
        
        this.textureDependencies.removeIf(rl -> rl.getPath().startsWith("#"));
	}

	@Override
	public Collection<Material> getTextures(IModelConfiguration owner, Function<ResourceLocation, IUnbakedModel> modelGetter, Set<Pair<String, String>> missingTextureErrors) {
		List<Material> ret = textureDependencies.stream()
				.map(rl -> new Material(AtlasTexture.LOCATION_BLOCKS_TEXTURE, rl))
    			.collect(Collectors.toList());
    	ret.addAll(vanillamodel.getTextures(modelGetter, missingTextureErrors));
        // Validate all texture metadata
    	for (Material tex : ret) {
            IMetadataSectionCTM meta;
			try {
				meta = ResourceUtil.getMetadata(ResourceUtil.spriteToAbsolute(tex.getTextureLocation()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
            if (meta != null) {
                if (meta.getType().requiredTextures() != meta.getAdditionalTextures().length + 1) {
                    throw new IllegalArgumentException(String.format("Texture type %s requires exactly %d textures. %d were provided.", meta.getType(), meta.getType().requiredTextures(), meta.getAdditionalTextures().length + 1));
                }
            }
    	}
    	return ret;
    }
	
	@Override
	public IBakedModel bake(IModelConfiguration owner, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform modelTransform, ItemOverrideList itemOverrides, ResourceLocation modelLocation) {
		return bake(bakery, spriteGetter, modelTransform, modelLocation);
	}
	
	public IBakedModel bake(ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform modelTransform, ResourceLocation modelLocation) {
        IBakedModel parent = vanillamodel.bakeModel(bakery, rl -> {
            TextureAtlasSprite sprite = spriteGetter.apply(rl);
            IMetadataSectionCTM chiselmeta = null;
            try {
                chiselmeta = ResourceUtil.getMetadata(sprite);
            } catch (IOException e) {}
            final IMetadataSectionCTM meta = chiselmeta;
            textures.computeIfAbsent(sprite.getName(), s -> {
                ICTMTexture<?> tex;
                if (meta == null) {
                    tex = new TextureNormal(TextureTypeNormal.INSTANCE, new TextureInfo(new TextureAtlasSprite[] { sprite }, Optional.empty(), null));
                } else {
                    tex = meta.makeTexture(sprite, spriteGetter);
                }
                layers |= 1 << (tex.getLayer() == null ? 7 : tex.getLayer().ordinal());
                return tex;
            });
            return sprite;
        }, modelTransform, modelLocation);
        if (spriteOverrides == null) {
            spriteOverrides = new Int2ObjectArrayMap<>();
            // Convert all primitive values into sprites
            for (Entry<Integer, JsonElement> e : overrides.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                    TextureAtlasSprite sprite = spriteGetter.apply(new Material(AtlasTexture.LOCATION_BLOCKS_TEXTURE, new ResourceLocation(e.getValue().getAsString())));
                    spriteOverrides.put(e.getKey(), sprite);
                }
            }
        }
        if (textureOverrides == null) {
            textureOverrides = new HashMap<>();
            for (Entry<Integer, IMetadataSectionCTM> e : metaOverrides.entrySet()) {
                List<BlockPartFace> matches = modelinfo.getElements().stream().flatMap(b -> b.mapFaces.values().stream()).filter(b -> b.tintIndex == e.getKey()).collect(Collectors.toList());
                Multimap<Material, BlockPartFace> bySprite = HashMultimap.create();
                // TODO 1.15 this isn't right
                matches.forEach(part -> bySprite.put(modelinfo.textures.getOrDefault(part.texture.substring(1), Either.right(part.texture)).left().get(), part));
                for (val e2 : bySprite.asMap().entrySet()) {
                    ResourceLocation texLoc = e2.getKey().getTextureLocation();
                    TextureAtlasSprite sprite = getOverrideSprite(e.getKey());
                    if (sprite == null) {
                    	sprite = spriteGetter.apply(new Material(AtlasTexture.LOCATION_BLOCKS_TEXTURE, texLoc));
                    }
                    ICTMTexture<?> tex = e.getValue().makeTexture(sprite, spriteGetter);
                    layers |= 1 << (tex.getLayer() == null ? 7 : tex.getLayer().ordinal());
                    textureOverrides.put(Pair.of(e.getKey(), texLoc.toString()), tex);
                }
            }
        }
        return new ModelBakedCTM(this, parent);
    }

    @Override
    public void load() {}

    @Override
    public Collection<ICTMTexture<?>> getCTMTextures() {
        return ImmutableList.<ICTMTexture<?>>builder().addAll(textures.values()).addAll(textureOverrides.values()).build();
    }
    
    @Override
    public ICTMTexture<?> getTexture(ResourceLocation iconName) {
        return textures.get(iconName);
    }

    @Override
    public boolean canRenderInLayer(BlockState state, RenderType layer) {
        // sign bit is used to signify that a layer-less (vanilla) texture is present
        return true; // TODO 1.15 (layers < 0 && state.getBlock().getBlockLayer() == layer) || ((layers >> layer.ordinal()) & 1) == 1;
    }

    @Override
    @Nullable
    public TextureAtlasSprite getOverrideSprite(int tintIndex) {
        return spriteOverrides.get(tintIndex);
    }
    
    @Override
    @Nullable
    public ICTMTexture<?> getOverrideTexture(int tintIndex, ResourceLocation sprite) {
        return textureOverrides.get(Pair.of(tintIndex, sprite));
    }
}
