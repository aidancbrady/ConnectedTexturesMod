package team.chisel.ctm.client.texture.ctx;

import java.util.EnumMap;

import javax.annotation.Nonnull;

import net.minecraft.block.BlockState;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockReader;
import team.chisel.ctm.api.texture.ITextureContext;
import team.chisel.ctm.client.texture.render.TextureCTM;
import team.chisel.ctm.client.util.CTMLogic;

public class TextureContextCTM implements ITextureContext {
    
	protected final TextureCTM<?> tex;
	
    private EnumMap<Direction, CTMLogic> ctmData = new EnumMap<>(Direction.class);

    private long data;

    public TextureContextCTM(@Nonnull BlockState state, IBlockReader world, BlockPos pos, TextureCTM<?> tex) {
    	this.tex = tex;
    	
        for (Direction face : Direction.values()) {
            CTMLogic ctm = createCTM(state);
            ctm.createSubmapIndices(world, pos, face);
            ctmData.put(face, ctm);
            this.data |= ctm.serialized() << (face.ordinal() * 10);
        }
    }
    
    protected CTMLogic createCTM(@Nonnull BlockState state) {
        CTMLogic ret = CTMLogic.getInstance()
                .ignoreStates(tex.ignoreStates())
                .stateComparator(tex::connectTo);
        ret.disableObscuredFaceCheck = tex.connectInside();
        return ret;
    }

    public CTMLogic getCTM(Direction face) {
        return ctmData.get(face);
    }

    @Override
    public long getCompressedData(){
        return this.data;
    }
}
