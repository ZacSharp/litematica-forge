package fi.dy.masa.litematica.gui.widgets;

import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.registry.Registry;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier.BlockMismatchEntry;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.SortCriteria;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.BlockUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.PositionUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetSchematicVerificationResult extends WidgetListEntrySortable<BlockMismatchEntry>
{
    private static final Random RAND = new Random();

    public static final String HEADER_EXPECTED = "litematica.gui.label.schematic_verifier.expected";
    public static final String HEADER_FOUND = "litematica.gui.label.schematic_verifier.found";
    public static final String HEADER_COUNT = "litematica.gui.label.schematic_verifier.count";

    private static int maxNameLengthExpected;
    private static int maxNameLengthFound;
    private static int maxCountLength;

    private final BlockRendererDispatcher blockModelShapes;
    private final GuiSchematicVerifier guiSchematicVerifier;
    private final WidgetListSchematicVerificationResults listWidget;
    private final SchematicVerifier verifier;
    private final BlockMismatchEntry mismatchEntry;
    @Nullable private final String header1;
    @Nullable private final String header2;
    @Nullable private final String header3;
    @Nullable private final BlockMismatchInfo mismatchInfo;
    private final int count;
    private final boolean isOdd;
    @Nullable private final ButtonGeneric buttonIgnore;

    public WidgetSchematicVerificationResult(int x, int y, int width, int height, boolean isOdd,
            WidgetListSchematicVerificationResults listWidget, GuiSchematicVerifier guiSchematicVerifier,
            BlockMismatchEntry entry, int listIndex)
    {
        super(x, y, width, height, entry, listIndex);

        this.columnCount = 3;
        this.blockModelShapes = this.mc.getBlockRendererDispatcher();
        this.mismatchEntry = entry;
        this.guiSchematicVerifier = guiSchematicVerifier;
        this.listWidget = listWidget;
        this.verifier = guiSchematicVerifier.getPlacement().getSchematicVerifier();
        this.isOdd = isOdd;

        // Main header
        if (entry.header1 != null && entry.header2 != null)
        {
            this.header1 = entry.header1;
            this.header2 = entry.header2;
            this.header3 = GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST;
            this.mismatchInfo = null;
            this.count = 0;
            this.buttonIgnore = null;
        }
        // Category title
        else if (entry.header1 != null)
        {
            this.header1 = entry.header1;
            this.header2 = null;
            this.header3 = null;
            this.mismatchInfo = null;
            this.count = 0;
            this.buttonIgnore = null;
        }
        // Mismatch entry
        else
        {
            this.header1 = null;
            this.header2 = null;
            this.header3 = null;
            this.mismatchInfo = new BlockMismatchInfo(entry.blockMismatch.stateExpected, entry.blockMismatch.stateFound);
            this.count = entry.blockMismatch.count;

            if (entry.mismatchType != MismatchType.CORRECT_STATE)
            {
                this.buttonIgnore = this.createButton(this.x + this.width, y + 1, ButtonListener.ButtonType.IGNORE_MISMATCH);
            }
            else
            {
                this.buttonIgnore = null;
            }
        }
    }

    public static void setMaxNameLengths(List<BlockMismatch> mismatches)
    {
        maxNameLengthExpected = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_EXPECTED) + GuiBase.TXT_RST);
        maxNameLengthFound    = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_FOUND) + GuiBase.TXT_RST);
        maxCountLength = 7 * StringUtils.getStringWidth("8");

        for (BlockMismatch entry : mismatches)
        {
            ItemStack stack = ItemUtils.getItemForState(entry.stateExpected);
            String name = BlockMismatchInfo.getDisplayName(entry.stateExpected, stack);
            maxNameLengthExpected = Math.max(maxNameLengthExpected, StringUtils.getStringWidth(name));

            stack = ItemUtils.getItemForState(entry.stateFound);
            name = BlockMismatchInfo.getDisplayName(entry.stateFound, stack);
            maxNameLengthFound = Math.max(maxNameLengthFound, StringUtils.getStringWidth(name));
        }

        maxCountLength = Math.max(maxCountLength, StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST));
    }

    private ButtonGeneric createButton(int x, int y, ButtonListener.ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, type.getDisplayName());
        return this.addButton(button, new ButtonListener(type, this.mismatchEntry, this.guiSchematicVerifier));
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.verifier.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.verifier.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        int x1 = this.x + 4;
        int x2 = x1 + maxNameLengthExpected + 40; // including item icon
        int x3 = x2 + maxNameLengthFound + 40;

        switch (column)
        {
            case 0: return x1;
            case 1: return x2;
            case 2: return x3;
            case 3: return x3 + maxCountLength + 20;
            default: return x1;
        }
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseClickedImpl(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        if (this.mismatchEntry.type != BlockMismatchEntry.Type.HEADER)
        {
            return false;
        }

        int column = this.getMouseOverColumn(mouseX, mouseY);

        switch (column)
        {
            case 0:
                this.verifier.setSortCriteria(SortCriteria.NAME_EXPECTED);
                break;
            case 1:
                this.verifier.setSortCriteria(SortCriteria.NAME_FOUND);
                break;
            case 2:
                this.verifier.setSortCriteria(SortCriteria.COUNT);
                break;
            default:
                return false;
        }

        // Re-create the widgets
        this.listWidget.refreshEntries();

        return true;
    }

    @Override
    public boolean canSelectAt(int mouseX, int mouseY, int mouseButton)
    {
        return (this.buttonIgnore == null || mouseX < this.buttonIgnore.getX()) && super.canSelectAt(mouseX, mouseY, mouseButton);
    }

    protected boolean shouldRenderAsSelected()
    {
        if (this.mismatchEntry.type == BlockMismatchEntry.Type.CATEGORY_TITLE)
        {
            return this.verifier.isMismatchCategorySelected(this.mismatchEntry.mismatchType);
        }
        else if (this.mismatchEntry.type == BlockMismatchEntry.Type.DATA)
        {
            return this.verifier.isMismatchEntrySelected(this.mismatchEntry.blockMismatch);
        }

        return false;
    }

    @Override
    public void render(int mouseX, int mouseY, boolean selected, MatrixStack matrixStack)
    {
        selected = this.shouldRenderAsSelected();

        // Default color for even entries
        int color = 0xA0303030;

        // Draw a lighter background for the hovered and the selected entry
        if (selected)
        {
            color = 0xA0707070;
        }
        else if (this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0505050;
        }
        // Draw a slightly darker background for odd entries
        else if (this.isOdd)
        {
            color = 0xA0101010;
        }

        RenderUtils.drawRect(this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int y = this.y + 7;
        color = 0xFFFFFFFF;

        if (this.header1 != null && this.header2 != null)
        {
            this.drawString(x1, y, color, this.header1, matrixStack);
            this.drawString(x2, y, color, this.header2, matrixStack);
            this.drawString(x3, y, color, this.header3, matrixStack);

            this.renderColumnHeader(mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
        }
        else if (this.header1 != null)
        {
            this.drawString(this.x + 4, this.y + 7, color, this.header1, matrixStack);
        }
        else if (this.mismatchInfo != null &&
                (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE ||
                 this.mismatchEntry.blockMismatch.stateExpected.isAir() == false)) 
        {
            this.drawString(x1 + 20, y, color, this.mismatchInfo.nameExpected, matrixStack);

            if (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE)
            {
                this.drawString(x2 + 20, y, color, this.mismatchInfo.nameFound, matrixStack);
            }

            this.drawString(x3, y, color, String.valueOf(this.count), matrixStack);

            y = this.y + 3;
            RenderUtils.drawRect(x1, y, 16, 16, 0x20FFFFFF); // light background for the item

            boolean useBlockModelConfig = Configs.Visuals.SCHEMATIC_VERIFIER_BLOCK_MODELS.getBooleanValue();
            boolean hasModelExpected = this.mismatchInfo.stateExpected.getRenderType() == BlockRenderType.MODEL;
            boolean hasModelFound    = this.mismatchInfo.stateFound.getRenderType() == BlockRenderType.MODEL;
            boolean isAirItemExpected = this.mismatchInfo.stackExpected.isEmpty();
            boolean isAirItemFound    = this.mismatchInfo.stackFound.isEmpty();
            boolean useBlockModelExpected = hasModelExpected && (isAirItemExpected || useBlockModelConfig || this.mismatchInfo.stateExpected.getBlock() == Blocks.FLOWER_POT);
            boolean useBlockModelFound    = hasModelFound    && (isAirItemFound    || useBlockModelConfig || this.mismatchInfo.stateFound.getBlock() == Blocks.FLOWER_POT);

            //RenderUtils.enableDiffuseLightingGui3D();

            IBakedModel model;

            if (useBlockModelExpected)
            {
                model = this.blockModelShapes.getModelForState(this.mismatchInfo.stateExpected);
                renderModelInGui(x1, y, 1, model, this.mismatchInfo.stateExpected, matrixStack, this.mc);
            }
            else
            {
                this.mc.getItemRenderer().renderItemAndEffectIntoGuiWithoutEntity(this.mismatchInfo.stackExpected, x1, y);
                this.mc.getItemRenderer().renderItemOverlayIntoGUI(this.textRenderer, this.mismatchInfo.stackExpected, x1, y, null);
            }

            if (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE)
            {
                RenderUtils.drawRect(x2, y, 16, 16, 0x20FFFFFF); // light background for the item

                if (useBlockModelFound)
                {
                    model = this.blockModelShapes.getModelForState(this.mismatchInfo.stateFound);
                    renderModelInGui(x2, y, 1, model, this.mismatchInfo.stateFound, matrixStack, this.mc);
                }
                else
                {
                    this.mc.getItemRenderer().renderItemAndEffectIntoGuiWithoutEntity(this.mismatchInfo.stackFound, x2, y);
                    this.mc.getItemRenderer().renderItemOverlayIntoGUI(this.textRenderer, this.mismatchInfo.stackFound, x2, y, null);
                }
            }

            //RenderUtils.disableDiffuseLighting();
            //RenderSystem.disableBlend();
        }

        super.render(mouseX, mouseY, selected, matrixStack);
    }

    @Override
    public void postRenderHovered(int mouseX, int mouseY, boolean selected, MatrixStack matrixStack)
    {
        if (this.mismatchInfo != null && this.buttonIgnore != null && mouseX < this.buttonIgnore.getX())
        {
            RenderSystem.pushMatrix();
            RenderSystem.translatef(0f, 0f, 200f);

            int x = mouseX + 10;
            int y = mouseY;
            int width = this.mismatchInfo.getTotalWidth();
            int height = this.mismatchInfo.getTotalHeight();

            if (x + width > GuiUtils.getCurrentScreen().width)
            {
                x = mouseX - width - 10;
            }

            if (y + height > GuiUtils.getCurrentScreen().height)
            {
                y = mouseY - height - 2;
            }

            this.mismatchInfo.render(x, y, this.mc, matrixStack);

            RenderSystem.popMatrix();
        }
    }

    public static class BlockMismatchInfo
    {
        private final BlockState stateExpected;
        private final BlockState stateFound;
        private final ItemStack stackExpected;
        private final ItemStack stackFound;
        private final String blockRegistrynameExpected;
        private final String blockRegistrynameFound;
        private final String nameExpected;
        private final String nameFound;
        private final int totalWidth;
        private final int totalHeight;
        private final int columnWidthExpected;

        public BlockMismatchInfo(BlockState stateExpected, BlockState stateFound)
        {
            this.stateExpected = stateExpected;
            this.stateFound = stateFound;

            this.stackExpected = ItemUtils.getItemForState(this.stateExpected);
            this.stackFound = ItemUtils.getItemForState(this.stateFound);

            Block blockExpected = this.stateExpected.getBlock();
            Block blockFound = this.stateFound.getBlock();
            ResourceLocation rl1 = Registry.BLOCK.getKey(blockExpected);
            ResourceLocation rl2 = Registry.BLOCK.getKey(blockFound);

            this.blockRegistrynameExpected = rl1 != null ? rl1.toString() : "<null>";
            this.blockRegistrynameFound = rl2 != null ? rl2.toString() : "<null>";

            this.nameExpected = getDisplayName(stateExpected, this.stackExpected);
            this.nameFound =    getDisplayName(stateFound,    this.stackFound);

            List<String> propsExpected = BlockUtils.getFormattedBlockStateProperties(this.stateExpected, " = ");
            List<String> propsFound = BlockUtils.getFormattedBlockStateProperties(this.stateFound, " = ");

            int w1 = Math.max(StringUtils.getStringWidth(this.nameExpected) + 20, StringUtils.getStringWidth(this.blockRegistrynameExpected));
            int w2 = Math.max(StringUtils.getStringWidth(this.nameFound) + 20, StringUtils.getStringWidth(this.blockRegistrynameFound));
            w1 = Math.max(w1, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(propsExpected));
            w2 = Math.max(w2, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(propsFound));

            this.columnWidthExpected = w1;
            this.totalWidth = this.columnWidthExpected + w2 + 40;
            this.totalHeight = Math.max(propsExpected.size(), propsFound.size()) * (StringUtils.getFontHeight() + 2) + 60;
        }

        public static String getDisplayName(BlockState state, ItemStack stack)
        {
            Block block = state.getBlock();
            String key = block.getTranslationKey() + ".name";
            String name = StringUtils.translate(key);
            name = key.equals(name) == false ? name : stack.getDisplayName().getString();

            return name;
        }

        public int getTotalWidth()
        {
            return this.totalWidth;
        }

        public int getTotalHeight()
        {
            return this.totalHeight;
        }

        public void render(int x, int y, Minecraft mc, MatrixStack matrixStack)
        {
            if (this.stateExpected != null && this.stateFound != null)
            {
                matrixStack.push();

                RenderUtils.drawOutlinedBox(x, y, this.totalWidth, this.totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);

                int x1 = x + 10;
                int x2 = x + this.columnWidthExpected + 30;
                y += 4;

                FontRenderer textRenderer = mc.fontRenderer;
                String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
                String strExpected = pre + StringUtils.translate("litematica.gui.label.schematic_verifier.expected") + GuiBase.TXT_RST;
                String strFound =    pre + StringUtils.translate("litematica.gui.label.schematic_verifier.found") + GuiBase.TXT_RST;
                textRenderer.drawString(matrixStack, strExpected, x1, y, 0xFFFFFFFF);
                textRenderer.drawString(matrixStack, strFound,    x2, y, 0xFFFFFFFF);

                y += 12;

                //RenderSystem.disableLighting();
                //RenderUtils.enableDiffuseLightingGui3D();

                boolean useBlockModelConfig = Configs.Visuals.SCHEMATIC_VERIFIER_BLOCK_MODELS.getBooleanValue();
                boolean hasModelExpected = this.stateExpected.getRenderType() == BlockRenderType.MODEL;
                boolean hasModelFound    = this.stateFound.getRenderType() == BlockRenderType.MODEL;
                boolean isAirItemExpected = this.stackExpected.isEmpty();
                boolean isAirItemFound    = this.stackFound.isEmpty();
                boolean useBlockModelExpected = hasModelExpected && (isAirItemExpected || useBlockModelConfig || this.stateExpected.getBlock() == Blocks.FLOWER_POT);
                boolean useBlockModelFound    = hasModelFound    && (isAirItemFound    || useBlockModelConfig || this.stateFound.getBlock() == Blocks.FLOWER_POT);
                BlockRendererDispatcher blockModelShapes = mc.getBlockRendererDispatcher();

                //mc.getRenderItem().zLevel += 100;
                RenderUtils.drawRect(x1, y, 16, 16, 0x50C0C0C0); // light background for the item
                RenderUtils.drawRect(x2, y, 16, 16, 0x50C0C0C0); // light background for the item

                int iconY = y;

                //RenderSystem.disableBlend();
                //RenderUtils.disableDiffuseLighting();

                textRenderer.drawString(matrixStack, this.nameExpected, x1 + 20, y + 4, 0xFFFFFFFF);
                textRenderer.drawString(matrixStack, this.nameFound,    x2 + 20, y + 4, 0xFFFFFFFF);

                y += 20;
                textRenderer.drawString(matrixStack, this.blockRegistrynameExpected, x1, y, 0xFF4060FF);
                textRenderer.drawString(matrixStack, this.blockRegistrynameFound,    x2, y, 0xFF4060FF);
                y += StringUtils.getFontHeight() + 4;

                List<String> propsExpected = BlockUtils.getFormattedBlockStateProperties(this.stateExpected, " = ");
                List<String> propsFound = BlockUtils.getFormattedBlockStateProperties(this.stateFound, " = ");
                RenderUtils.renderText(x1, y, 0xFFB0B0B0, propsExpected, matrixStack);
                RenderUtils.renderText(x2, y, 0xFFB0B0B0, propsFound, matrixStack);

                IBakedModel model;

                //RenderUtils.enableDiffuseLightingGui3D();

                if (useBlockModelExpected)
                {
                    model = blockModelShapes.getModelForState(this.stateExpected);
                    renderModelInGui(x1, iconY, 1, model, this.stateExpected, matrixStack, mc);
                }
                else
                {
                    mc.getItemRenderer().renderItemAndEffectIntoGuiWithoutEntity(this.stackExpected, x1, iconY);
                    mc.getItemRenderer().renderItemOverlayIntoGUI(textRenderer, this.stackExpected, x1, iconY, null);
                }

                if (useBlockModelFound)
                {
                    model = blockModelShapes.getModelForState(this.stateFound);
                    renderModelInGui(x2, iconY, 1, model, this.stateFound, matrixStack, mc);
                }
                else
                {
                    mc.getItemRenderer().renderItemAndEffectIntoGuiWithoutEntity(this.stackFound, x2, iconY);
                    mc.getItemRenderer().renderItemOverlayIntoGUI(textRenderer, this.stackFound, x2, iconY, null);
                }

                //mc.getRenderItem().zLevel -= 100;

                matrixStack.pop();
            }
        }
    }

    public static void renderModelInGui(int x, int y, float z,
                                        IBakedModel model, BlockState state,
                                        MatrixStack matrixStack, Minecraft mc)
    {
        if (state.getBlock() == Blocks.AIR)
        {
            return;
        }

        RenderUtils.bindTexture(PlayerContainer.LOCATION_BLOCKS_TEXTURE);
        mc.getTextureManager().getTexture(PlayerContainer.LOCATION_BLOCKS_TEXTURE).setBlurMipmapDirect(false, false);

        RenderUtils.setupBlend();
        RenderUtils.color(1f, 1f, 1f, 1f);

        matrixStack.push();
        matrixStack.translate(x + 8.0, y + 8.0, z + 100.0);
        matrixStack.scale(16, -16, 16);

        matrixStack.rotate(new Quaternion(Vector3f.XP, 30, true));
        matrixStack.rotate(new Quaternion(Vector3f.YP, 225, true));
        matrixStack.scale(0.625f, 0.625f, 0.625f);
        matrixStack.translate(-0.5, -0.5, -0.5);

        renderModel(model, state, matrixStack);

        matrixStack.pop();
    }

    private static void renderModel(IBakedModel model, BlockState state, MatrixStack matrixStack)
    {
        if (model.isBuiltInRenderer() == false)
        {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            IRenderTypeBuffer.Impl immediate = IRenderTypeBuffer.getImpl(buffer);
            IVertexBuilder vertexConsumer = immediate.getBuffer(RenderType.getTranslucent());
            MatrixStack.Entry matrixEntry = matrixStack.getLast();

            int l = LightTexture.packLight(15, 15);
            int[] light = new int[] { l, l, l, l };
            float[] brightness = new float[] { 0.75f, 0.75f, 0.75f, 1.0f };

            RenderHelper.setupGui3DDiffuseLighting();

            for (Direction face : PositionUtils.ALL_DIRECTIONS)
            {
                RAND.setSeed(0);
                renderQuads(model.getQuads(state, face, RAND), brightness, light, matrixEntry, vertexConsumer);
            }

            RAND.setSeed(0);
            renderQuads(model.getQuads(state, null, RAND), brightness, light, matrixEntry, vertexConsumer);

            //tessellator.draw();
            immediate.finish();
        }
    }

    private static void renderQuads(List<BakedQuad> quads, float[] brightness, int[] light,
                                    MatrixStack.Entry matrixEntry, IVertexBuilder vertexConsumer)
    {
        for (BakedQuad quad : quads)
        {
            renderQuad(quad, brightness, light, matrixEntry, vertexConsumer);
        }
    }

    private static void renderQuad(BakedQuad quad, float[] brightness, int[] light,
                                   MatrixStack.Entry matrixEntry, IVertexBuilder vertexConsumer)
    {
        vertexConsumer.addQuad(matrixEntry, quad, brightness, 1.0f, 1.0f, 1.0f, light, OverlayTexture.NO_OVERLAY, true);
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final ButtonType type;
        private final GuiSchematicVerifier guiSchematicVerifier;
        private final BlockMismatchEntry mismatchEntry;

        public ButtonListener(ButtonType type, BlockMismatchEntry mismatchEntry, GuiSchematicVerifier guiSchematicVerifier)
        {
            this.type = type;
            this.mismatchEntry = mismatchEntry;
            this.guiSchematicVerifier = guiSchematicVerifier;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.IGNORE_MISMATCH)
            {
                this.guiSchematicVerifier.getPlacement().getSchematicVerifier().ignoreStateMismatch(this.mismatchEntry.blockMismatch);
                this.guiSchematicVerifier.initGui();
            }
        }

        public enum ButtonType
        {
            IGNORE_MISMATCH ("litematica.gui.button.schematic_verifier.ignore");

            private final String translationKey;

            private ButtonType(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }
}
