package fi.dy.masa.litematica.render.schematic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderTypeBuffers;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.crash.ReportedException;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraft.world.chunk.Chunk;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo.OverlayRenderType;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.SubChunkPos;

public class WorldRendererSchematic
{
    private final Minecraft mc;
    private final EntityRendererManager entityRenderDispatcher;
    private final BlockRendererDispatcher blockRenderManager;
    private final BlockModelRendererSchematic blockModelRenderer;
    private final Set<TileEntity> blockEntities = new HashSet<>();
    private final List<ChunkRendererSchematicVbo> renderInfos = new ArrayList<>(1024);
    private final RenderTypeBuffers bufferBuilders;
    private Set<ChunkRendererSchematicVbo> chunksToUpdate = new LinkedHashSet<>();
    private WorldSchematic world;
    private ChunkRenderDispatcherSchematic chunkRendererDispatcher;
    private double lastCameraChunkUpdateX = Double.MIN_VALUE;
    private double lastCameraChunkUpdateY = Double.MIN_VALUE;
    private double lastCameraChunkUpdateZ = Double.MIN_VALUE;
    private int cameraChunkX = Integer.MIN_VALUE;
    private int cameraChunkY = Integer.MIN_VALUE;
    private int cameraChunkZ = Integer.MIN_VALUE;
    private double lastCameraX = Double.MIN_VALUE;
    private double lastCameraY = Double.MIN_VALUE;
    private double lastCameraZ = Double.MIN_VALUE;
    private float lastCameraPitch = Float.MIN_VALUE;
    private float lastCameraYaw = Float.MIN_VALUE;
    private ChunkRenderDispatcherLitematica renderDispatcher;
    private final IChunkRendererFactory renderChunkFactory;
    //private ShaderGroup entityOutlineShader;
    //private boolean entityOutlinesRendered;

    private int renderDistanceChunks = -1;
    private int renderEntitiesStartupCounter = 2;
    private int countEntitiesTotal;
    private int countEntitiesRendered;
    private int countEntitiesHidden;

    private double lastTranslucentSortX;
    private double lastTranslucentSortY;
    private double lastTranslucentSortZ;
    private boolean displayListEntitiesDirty = true;

    public WorldRendererSchematic(Minecraft mc)
    {
        this.mc = mc;
        this.entityRenderDispatcher = mc.getRenderManager();
        this.bufferBuilders = mc.getRenderTypeBuffers();

        this.renderChunkFactory = ChunkRendererSchematicVbo::new;

        this.blockRenderManager = Minecraft.getInstance().getBlockRendererDispatcher();
        this.blockModelRenderer = new BlockModelRendererSchematic(mc.getBlockColors());
    }

    public void markNeedsUpdate()
    {
        this.displayListEntitiesDirty = true;
    }

    public boolean hasWorld()
    {
        return this.world != null;
    }

    public String getDebugInfoRenders()
    {
        int rcTotal = this.chunkRendererDispatcher != null ? this.chunkRendererDispatcher.renderers.length : 0;
        int rcRendered = this.chunkRendererDispatcher != null ? this.getRenderedChunks() : 0;
        return String.format("C: %d/%d %sD: %d, L: %d, %s", rcRendered, rcTotal, this.mc.renderChunksMany ? "(s) " : "", this.renderDistanceChunks, 0, this.renderDispatcher == null ? "null" : this.renderDispatcher.getDebugInfo());
    }

    public String getDebugInfoEntities()
    {
        return "E: " + this.countEntitiesRendered + "/" + this.countEntitiesTotal + ", B: " + this.countEntitiesHidden;
    }

    protected int getRenderedChunks()
    {
        int count = 0;

        for (ChunkRendererSchematicVbo chunkRenderer : this.renderInfos)
        {
            ChunkRenderDataSchematic data = chunkRenderer.chunkRenderData;

            if (data != ChunkRenderDataSchematic.EMPTY && data.isEmpty() == false)
            {
                ++count;
            }
        }

        return count;
    }

    public void setWorldAndLoadRenderers(@Nullable WorldSchematic worldSchematic)
    {
        this.lastCameraChunkUpdateX = Double.MIN_VALUE;
        this.lastCameraChunkUpdateY = Double.MIN_VALUE;
        this.lastCameraChunkUpdateZ = Double.MIN_VALUE;
        this.cameraChunkX = Integer.MIN_VALUE;
        this.cameraChunkY = Integer.MIN_VALUE;
        this.cameraChunkZ = Integer.MIN_VALUE;
        //this.renderManager.setWorld(worldClientIn);
        this.world = worldSchematic;

        if (worldSchematic != null)
        {
            this.loadRenderers();
        }
        else
        {
            this.chunksToUpdate.clear();
            this.renderInfos.clear();

            if (this.chunkRendererDispatcher != null)
            {
                this.chunkRendererDispatcher.delete();
                this.chunkRendererDispatcher = null;
            }

            if (this.renderDispatcher != null)
            {
                this.renderDispatcher.stopWorkerThreads();
            }

            this.renderDispatcher = null;
            this.blockEntities.clear();
        }
    }

    public void loadRenderers()
    {
        if (this.hasWorld())
        {
            if (this.renderDispatcher == null)
            {
                this.renderDispatcher = new ChunkRenderDispatcherLitematica();
            }

            this.displayListEntitiesDirty = true;
            this.renderDistanceChunks = this.mc.gameSettings.renderDistanceChunks;

            if (this.chunkRendererDispatcher != null)
            {
                this.chunkRendererDispatcher.delete();
            }

            this.stopChunkUpdates();

            synchronized (this.blockEntities)
            {
                this.blockEntities.clear();
            }

            this.chunkRendererDispatcher = new ChunkRenderDispatcherSchematic(this.world, this.renderDistanceChunks, this, this.renderChunkFactory);

            Entity entity = this.mc.getRenderViewEntity();

            if (entity != null)
            {
                this.chunkRendererDispatcher.updateCameraPosition(entity.getPosX(), entity.getPosZ());
            }

            this.renderEntitiesStartupCounter = 2;
        }
    }

    protected void stopChunkUpdates()
    {
        this.chunksToUpdate.clear();
        this.renderDispatcher.stopChunkUpdates();
    }

    public void setupTerrain(ActiveRenderInfo camera, ClippingHelper frustum, int frameCount, boolean playerSpectator)
    {
        this.world.getProfiler().startSection("setup_terrain");

        if (this.chunkRendererDispatcher == null || this.mc.gameSettings.renderDistanceChunks != this.renderDistanceChunks)
        {
            this.loadRenderers();
        }

        Entity entity = this.mc.getRenderViewEntity();

        if (entity == null)
        {
            entity = this.mc.player;
        }

        //camera.update(this.world, entity, this.mc.options.perspective > 0, this.mc.options.perspective == 2, this.mc.getTickDelta());

        this.world.getProfiler().startSection("camera");

        double entityX = entity.getPosX();
        double entityY = entity.getPosY();
        double entityZ = entity.getPosZ();

        double diffX = entityX - this.lastCameraChunkUpdateX;
        double diffY = entityY - this.lastCameraChunkUpdateY;
        double diffZ = entityZ - this.lastCameraChunkUpdateZ;

        if (this.cameraChunkX != entity.chunkCoordX ||
            this.cameraChunkY != entity.chunkCoordY ||
            this.cameraChunkZ != entity.chunkCoordZ ||
            diffX * diffX + diffY * diffY + diffZ * diffZ > 16.0D)
        {
            this.lastCameraChunkUpdateX = entityX;
            this.lastCameraChunkUpdateY = entityY;
            this.lastCameraChunkUpdateZ = entityZ;
            this.cameraChunkX = entity.chunkCoordX;
            this.cameraChunkY = entity.chunkCoordY;
            this.cameraChunkZ = entity.chunkCoordZ;
            this.chunkRendererDispatcher.updateCameraPosition(entityX, entityZ);
        }

        this.world.getProfiler().endStartSection("renderlist_camera");

        Vector3d cameraPos = camera.getProjectedView();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;

        this.renderDispatcher.setCameraPosition(cameraPos);

        this.world.getProfiler().endStartSection("culling");
        BlockPos viewPos = new BlockPos(cameraX, cameraY + (double) entity.getEyeHeight(), cameraZ);
        final int centerChunkX = (viewPos.getX() >> 4);
        final int centerChunkZ = (viewPos.getZ() >> 4);
        final int renderDistance = this.mc.gameSettings.renderDistanceChunks;
        SubChunkPos viewSubChunk = new SubChunkPos(centerChunkX, viewPos.getY() >> 4, centerChunkZ);
        BlockPos viewPosSubChunk = new BlockPos(viewSubChunk.getX() << 4, viewSubChunk.getY() << 4, viewSubChunk.getZ() << 4);

        this.displayListEntitiesDirty = this.displayListEntitiesDirty || this.chunksToUpdate.isEmpty() == false ||
                entityX != this.lastCameraX ||
                entityY != this.lastCameraY ||
                entityZ != this.lastCameraZ ||
                entity.rotationPitch != this.lastCameraPitch ||
                entity.rotationYaw != this.lastCameraYaw;
        this.lastCameraX = cameraX;
        this.lastCameraY = cameraY;
        this.lastCameraZ = cameraZ;
        this.lastCameraPitch = camera.getPitch();
        this.lastCameraYaw = camera.getYaw();

        this.world.getProfiler().endStartSection("update");

        if (this.displayListEntitiesDirty)
        {
            this.world.getProfiler().startSection("fetch");

            this.displayListEntitiesDirty = false;
            this.renderInfos.clear();

            Set<SubChunkPos> set = DataManager.getSchematicPlacementManager().getAllTouchedSubChunks();
            List<SubChunkPos> positions = new ArrayList<>(set.size());
            positions.addAll(set);
            Collections.sort(positions, new SubChunkPos.DistanceComparator(viewSubChunk));

            //Queue<SubChunkPos> queuePositions = new PriorityQueue<>(new SubChunkPos.DistanceComparator(viewSubChunk));
            //queuePositions.addAll(set);

            //if (GuiBase.isCtrlDown()) System.out.printf("sorted positions: %d\n", positions.size());

            this.world.getProfiler().endStartSection("iteration");

            //while (queuePositions.isEmpty() == false)
            for (int i = 0; i < positions.size(); ++i)
            {
                //SubChunkPos subChunk = queuePositions.poll();
                SubChunkPos subChunk = positions.get(i);

                // Only render sub-chunks that are within the client's render distance, and that
                // have been already properly loaded on the client
                if (Math.abs(subChunk.getX() - centerChunkX) <= renderDistance &&
                    Math.abs(subChunk.getZ() - centerChunkZ) <= renderDistance &&
                    this.world.getChunkProvider().chunkExists(subChunk.getX(), subChunk.getZ()))
                {
                    BlockPos subChunkCornerPos = new BlockPos(subChunk.getX() << 4, subChunk.getY() << 4, subChunk.getZ() << 4);
                    ChunkRendererSchematicVbo chunkRenderer = this.chunkRendererDispatcher.getChunkRenderer(subChunkCornerPos);

                    if (chunkRenderer != null)
                    {
                        if (frustum.isBoundingBoxInFrustum(chunkRenderer.getBoundingBox()))
                        {
                            //if (GuiBase.isCtrlDown()) System.out.printf("add @ %s\n", subChunk);
                            if (chunkRenderer.needsUpdate() && subChunkCornerPos.equals(viewPosSubChunk))
                            {
                                chunkRenderer.setNeedsUpdate(true);
                            }

                            this.renderInfos.add(chunkRenderer);
                        }
                    }
                }
            }

            this.world.getProfiler().endSection();
        }

        this.world.getProfiler().endStartSection("rebuild_near");
        Set<ChunkRendererSchematicVbo> set = this.chunksToUpdate;
        this.chunksToUpdate = new LinkedHashSet<>();

        for (ChunkRendererSchematicVbo chunkRendererTmp : this.renderInfos)
        {
            if (chunkRendererTmp.needsUpdate() || set.contains(chunkRendererTmp))
            {
                this.displayListEntitiesDirty = true;
                BlockPos pos = chunkRendererTmp.getOrigin().add(8, 8, 8);
                boolean isNear = pos.distanceSq(viewPos) < 1024.0D;

                if (chunkRendererTmp.needsImmediateUpdate() == false && isNear == false)
                {
                    this.chunksToUpdate.add(chunkRendererTmp);
                }
                else
                {
                    //if (GuiBase.isCtrlDown()) System.out.printf("====== update now\n");
                    this.world.getProfiler().startSection("build_near");

                    this.renderDispatcher.updateChunkNow(chunkRendererTmp);
                    chunkRendererTmp.clearNeedsUpdate();

                    this.world.getProfiler().endSection();
                }
            }
        }

        this.chunksToUpdate.addAll(set);

        this.world.getProfiler().endSection();
        this.world.getProfiler().endSection();
    }

    public void updateChunks(long finishTimeNano)
    {
        this.displayListEntitiesDirty |= this.renderDispatcher.runChunkUploads(finishTimeNano);

        if (this.chunksToUpdate.isEmpty() == false)
        {
            Iterator<ChunkRendererSchematicVbo> iterator = this.chunksToUpdate.iterator();

            while (iterator.hasNext())
            {
                ChunkRendererSchematicVbo renderChunk = iterator.next();
                boolean flag;

                if (renderChunk.needsImmediateUpdate())
                {
                    flag = this.renderDispatcher.updateChunkNow(renderChunk);
                }
                else
                {
                    flag = this.renderDispatcher.updateChunkLater(renderChunk);
                }

                if (!flag)
                {
                    break;
                }

                renderChunk.clearNeedsUpdate();
                iterator.remove();
                long i = finishTimeNano - System.nanoTime();

                if (i < 0L)
                {
                    break;
                }
            }
        }
    }

    public int renderBlockLayer(RenderType renderLayer, MatrixStack matrices, ActiveRenderInfo camera)
    {
        this.world.getProfiler().startSection("render_block_layer_" + renderLayer.toString());

        boolean isTranslucent = renderLayer == RenderType.getTranslucent();

        renderLayer.setupRenderState();
        //RenderUtils.disableDiffuseLighting();
        Vector3d cameraPos = camera.getProjectedView();
        double x = cameraPos.x;
        double y = cameraPos.y;
        double z = cameraPos.z;

        if (isTranslucent)
        {
            this.world.getProfiler().startSection("translucent_sort");
            double diffX = x - this.lastTranslucentSortX;
            double diffY = y - this.lastTranslucentSortY;
            double diffZ = z - this.lastTranslucentSortZ;

            if (diffX * diffX + diffY * diffY + diffZ * diffZ > 1.0D)
            {
                this.lastTranslucentSortX = x;
                this.lastTranslucentSortY = y;
                this.lastTranslucentSortZ = z;
                int i = 0;

                for (ChunkRendererSchematicVbo chunkRenderer : this.renderInfos)
                {
                    if ((chunkRenderer.getChunkRenderData().isBlockLayerStarted(renderLayer) ||
                        (chunkRenderer.getChunkRenderData() != ChunkRenderDataSchematic.EMPTY && chunkRenderer.hasOverlay())) && i++ < 15)
                    {
                        this.renderDispatcher.updateTransparencyLater(chunkRenderer);
                    }
                }
            }

            this.world.getProfiler().endSection();
        }

        this.world.getProfiler().startSection("filter_empty");
        this.world.getProfiler().endStartSection("render");

        boolean reverse = isTranslucent;
        int startIndex = reverse ? this.renderInfos.size() - 1 : 0;
        int stopIndex = reverse ? -1 : this.renderInfos.size();
        int increment = reverse ? -1 : 1;
        int count = 0;

        for (int i = startIndex; i != stopIndex; i += increment)
        {
            ChunkRendererSchematicVbo renderer = this.renderInfos.get(i);

            if (renderer.getChunkRenderData().isBlockLayerEmpty(renderLayer) == false)
            {
                BlockPos chunkOrigin = renderer.getOrigin();
                VertexBuffer buffer = renderer.getBlocksVertexBufferByLayer(renderLayer);

                matrices.push();
                matrices.translate((double) chunkOrigin.getX() - x, (double) chunkOrigin.getY() - y, (double) chunkOrigin.getZ() - z);

                buffer.bindBuffer();
                renderLayer.getVertexFormat().setupBufferState(0L);
                buffer.draw(matrices.getLast().getMatrix(), GL11.GL_QUADS);

                matrices.pop();
                ++count;
            }
        }

        VertexBuffer.unbindBuffer();
        RenderSystem.clearCurrentColor();
        renderLayer.getVertexFormat().clearBufferState();
        renderLayer.clearRenderState();

        this.world.getProfiler().endSection();
        this.world.getProfiler().endSection();

        return count;
    }

    public void renderBlockOverlays(MatrixStack matrices, ActiveRenderInfo camera)
    {
        this.renderBlockOverlay(OverlayRenderType.OUTLINE, matrices, camera);
        this.renderBlockOverlay(OverlayRenderType.QUAD, matrices, camera);
    }

    private void renderBlockOverlay(OverlayRenderType type, MatrixStack matrices, ActiveRenderInfo camera)
    {
        //RenderType renderLayer = RenderType.getTranslucent();
        //renderLayer.startDrawing();
        //RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Vector3d cameraPos = camera.getProjectedView();
        double x = cameraPos.x;
        double y = cameraPos.y;
        double z = cameraPos.z;

        this.world.getProfiler().startSection("overlay_" + type.name());
        this.world.getProfiler().endStartSection("render");

        for (int i = this.renderInfos.size() - 1; i >= 0; --i)
        {
            ChunkRendererSchematicVbo renderer = this.renderInfos.get(i);

            if (renderer.getChunkRenderData() != ChunkRenderDataSchematic.EMPTY && renderer.hasOverlay())
            {
                ChunkRenderDataSchematic compiledChunk = renderer.getChunkRenderData();

                if (compiledChunk.isOverlayTypeEmpty(type) == false)
                {
                    BlockPos chunkOrigin = renderer.getOrigin();
                    VertexBuffer buffer = renderer.getOverlayVertexBuffer(type);

                    matrices.push();
                    matrices.translate((double) chunkOrigin.getX() - x, (double) chunkOrigin.getY() - y, (double) chunkOrigin.getZ() - z);

                    buffer.bindBuffer();
                    DefaultVertexFormats.POSITION_COLOR.setupBufferState(0L);
                    buffer.draw(matrices.getLast().getMatrix(), type.getGlMode());

                    matrices.pop();
                }
            }
        }

        VertexBuffer.unbindBuffer();
        RenderSystem.clearCurrentColor();
        DefaultVertexFormats.POSITION_COLOR.clearBufferState();

        RenderSystem.disableBlend();
        //RenderSystem.enableTexture();
        //renderLayer.endDrawing();

        this.world.getProfiler().endSection();
    }

    public boolean renderBlock(IBlockDisplayReader world, BlockState state, BlockPos pos, MatrixStack matrices, BufferBuilder bufferBuilderIn)
    {
        try
        {
            BlockRenderType renderType = state.getRenderType();

            if (renderType == BlockRenderType.INVISIBLE)
            {
                return false;
            }
            else
            {
                switch (renderType)
                {
                    case MODEL:
                        return this.blockModelRenderer.renderModel(world, this.getModelForState(state), state, pos, matrices, bufferBuilderIn, state.getPositionRandom(pos));
                    case ENTITYBLOCK_ANIMATED:
                        return false;
                    default:
                        return false;
                }
            }
        }
        catch (Throwable throwable)
        {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, pos, state);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderFluid(IBlockDisplayReader world, FluidState state, BlockPos pos, BufferBuilder bufferBuilderIn)
    {
        return this.blockRenderManager.renderFluid(pos, world, bufferBuilderIn, state);
    }

    public IBakedModel getModelForState(BlockState state)
    {
        if (state.getRenderType() == BlockRenderType.ENTITYBLOCK_ANIMATED)
        {
            return this.blockRenderManager.getBlockModelShapes().getModelManager().getMissingModel();
        }

        return this.blockRenderManager.getModelForState(state);
    }

    public void renderEntities(ActiveRenderInfo camera, ClippingHelper frustum, MatrixStack matrices, float partialTicks)
    {
        if (this.renderEntitiesStartupCounter > 0)
        {
            --this.renderEntitiesStartupCounter;
        }
        else
        {
            this.world.getProfiler().startSection("prepare");

            double cameraX = camera.getProjectedView().x;
            double cameraY = camera.getProjectedView().y;
            double cameraZ = camera.getProjectedView().z;

            TileEntityRendererDispatcher.instance.prepare(this.world, this.mc.getTextureManager(), this.mc.fontRenderer, camera, this.mc.objectMouseOver);
            this.entityRenderDispatcher.cacheActiveRenderInfo(this.world, camera, this.mc.pointedEntity);

            this.countEntitiesTotal = 0;
            this.countEntitiesRendered = 0;
            this.countEntitiesHidden = 0;

            this.countEntitiesTotal = this.world.getRegularEntityCount();

            this.world.getProfiler().endStartSection("regular_entities");
            //List<Entity> entitiesMultipass = Lists.<Entity>newArrayList();

            IRenderTypeBuffer.Impl entityVertexConsumers = this.bufferBuilders.getBufferSource();
            LayerRange layerRange = DataManager.getRenderLayerRange();

            for (ChunkRendererSchematicVbo chunkRenderer : this.renderInfos)
            {
                BlockPos pos = chunkRenderer.getOrigin();
                Chunk chunk = (Chunk) this.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                ClassInheritanceMultiMap<Entity> list = chunk.getEntityLists()[pos.getY() >> 4];

                if (list.isEmpty() == false)
                {
                    for (Entity entityTmp : list)
                    {
                        if (layerRange.isPositionWithinRange((int) entityTmp.getPosX(), (int) entityTmp.getPosY(), (int) entityTmp.getPosZ()) == false)
                        {
                            continue;
                        }

                        boolean shouldRender = this.entityRenderDispatcher.shouldRender(entityTmp, frustum, cameraX, cameraY, cameraZ);

                        if (shouldRender)
                        {
                            double x = entityTmp.getPosX() - cameraX;
                            double y = entityTmp.getPosY() - cameraY;
                            double z = entityTmp.getPosZ() - cameraZ;

                            this.entityRenderDispatcher.renderEntityStatic(entityTmp, x, y, z, entityTmp.rotationYaw, partialTicks, matrices, entityVertexConsumers, this.entityRenderDispatcher.getPackedLight(entityTmp, partialTicks));
                            ++this.countEntitiesRendered;
                        }
                    }
                }
            }

            this.world.getProfiler().endStartSection("block_entities");

            for (ChunkRendererSchematicVbo chunkRenderer : this.renderInfos)
            {
                ChunkRenderDataSchematic data = chunkRenderer.getChunkRenderData();
                List<TileEntity> tiles = data.getBlockEntities();

                if (tiles.isEmpty() == false) 
                {
                    BlockPos chunkOrigin = chunkRenderer.getOrigin();
                    ChunkSchematic chunk = this.world.getChunkProvider().getChunkForLight(chunkOrigin.getX() >> 4, chunkOrigin.getZ() >> 4);

                    if (chunk != null && data instanceof ChunkRenderDataSchematic &&
                        data.getTimeBuilt() >= chunk.getTimeCreated())
                    {
                        for (TileEntity te : tiles)
                        {
                            try
                            {
                                BlockPos pos = te.getPos();
                                matrices.push();
                                matrices.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);

                                TileEntityRendererDispatcher.instance.renderTileEntity(te, partialTicks, matrices, entityVertexConsumers);

                                matrices.pop();
                            }
                            catch (Exception e)
                            {
                            }
                        }
                    }
                }
            }

            synchronized (this.blockEntities)
            {
                for (TileEntity te : this.blockEntities)
                {
                    try
                    {
                        BlockPos pos = te.getPos();
                        matrices.push();
                        matrices.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);

                        TileEntityRendererDispatcher.instance.renderTileEntity(te, partialTicks, matrices, entityVertexConsumers);

                        matrices.pop();
                    }
                    catch (Exception e)
                    {
                    }
                }
            }

            this.world.getProfiler().endSection();
        }
    }

    /*
    private boolean isOutlineActive(Entity entityIn, Entity viewer, ActiveRenderInfo camera)
    {
        boolean sleeping = viewer instanceof LivingEntity && ((LivingEntity) viewer).isSleeping();

        if (entityIn == viewer && this.mc.options.perspective == 0 && sleeping == false)
        {
            return false;
        }
        else if (entityIn.isGlowing())
        {
            return true;
        }
        else if (this.mc.player.isSpectator() && this.mc.options.keySpectatorOutlines.isPressed() && entityIn instanceof PlayerEntity)
        {
            return entityIn.ignoreFrustumCheck || camera.isBoundingBoxInFrustum(entityIn.getBoundingBox()) || entityIn.isRidingOrBeingRiddenBy(this.mc.player);
        }
        else
        {
            return false;
        }
    }
    */

    public void updateBlockEntities(Collection<TileEntity> toRemove, Collection<TileEntity> toAdd)
    {
        synchronized (this.blockEntities)
        {
            this.blockEntities.removeAll(toRemove);
            this.blockEntities.addAll(toAdd);
        }
    }

    public void scheduleChunkRenders(int chunkX, int chunkY, int chunkZ)
    {
        this.chunkRendererDispatcher.scheduleChunkRender(chunkX, chunkY, chunkZ, false);
    }
}
