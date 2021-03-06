/*
 * Copyright (c) 2015-present, Horcrux.
 * All rights reserved.
 *
 * This source code is licensed under the MIT-style license found in the
 * LICENSE file in the root directory of this source tree.
 */


package com.horcrux.svg;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.JavaOnlyArray;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.DisplayMetricsHolder;
import com.facebook.react.uimanager.LayoutShadowNode;
import com.facebook.react.uimanager.OnLayoutEvent;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.EventDispatcher;

import javax.annotation.Nullable;

import static com.horcrux.svg.FontData.DEFAULT_FONT_SIZE;

abstract class VirtualNode<T> extends LayoutShadowNode {
    /*
        N[1/Sqrt[2], 36]
        The inverse of the square root of 2.
        Provide enough digits for the 128-bit IEEE quad (36 significant digits).
    */
    private static final double M_SQRT1_2l = 0.707106781186547524400844362104849039;

    static final float MIN_OPACITY_FOR_DRAW = 0.01f;

    @Override
    public void setReactTag(int reactTag) {
        super.setReactTag(reactTag);
        vm.setShadowNode(reactTag, this);
    }

    RenderableViewManager<VirtualNode<T>> vm;

    private static final float[] sRawMatrix = new float[]{
        1, 0, 0,
        0, 1, 0,
        0, 0, 1
    };
    float mOpacity = 1f;
    Matrix mMatrix = new Matrix();
    Matrix mTransform = new Matrix();
    Matrix mInvMatrix = new Matrix();
    boolean mInvertible = true;
    private RectF mClientRect;

    private int mClipRule;
    private @Nullable String mClipPath;
    @Nullable String mMask;

    private static final int CLIP_RULE_EVENODD = 0;
    private static final int CLIP_RULE_NONZERO = 1;

    final float mScale;
    private boolean mResponsible;
    String mName;

    private SvgViewShadowNode mSvgShadowNode;
    private Path mCachedClipPath;
    private GroupShadowNode mTextRoot;
    private double fontSize = -1;
    private double canvasDiagonal = -1;
    private float canvasHeight = -1;
    private float canvasWidth = -1;
    private GlyphContext glyphContext;

    Path mPath;
    Path mFillPath;
    Path mStrokePath;
    RectF mBox;
    Region mRegion;
    Region mStrokeRegion;
    Region mClipRegion;
    Path mClipRegionPath;

    VirtualNode() {
        mScale = DisplayMetricsHolder.getScreenDisplayMetrics().density;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public boolean isVirtualAnchor() {
        return false;
    }

    @Override
    public void markUpdated() {
        super.markUpdated();
        clearPath();
    }

    private void clearPath() {
        canvasDiagonal = -1;
        canvasHeight = -1;
        canvasWidth = -1;
        fontSize = -1;
        mRegion = null;
        mPath = null;
    }

    void releaseCachedPath() {
        clearPath();
        traverseChildren(new NodeRunnable() {
            public void run(ReactShadowNode node) {
                if (node instanceof VirtualNode) {
                    ((VirtualNode)node).releaseCachedPath();
                }
            }
        });
    }

    @Nullable
    GroupShadowNode getTextRoot() {
        VirtualNode node = this;
        if (mTextRoot == null) {
            while (node != null) {
                if (node instanceof GroupShadowNode && ((GroupShadowNode) node).getGlyphContext() != null) {
                    mTextRoot = (GroupShadowNode)node;
                    break;
                }

                ReactShadowNode parent = node.getParent();

                if (!(parent instanceof VirtualNode)) {
                    node = null;
                } else {
                    node = (VirtualNode)parent;
                }
            }
        }

        return mTextRoot;
    }

    @Nullable
    GroupShadowNode getParentTextRoot() {
        ReactShadowNode parent = this.getParent();
        if (!(parent instanceof VirtualNode)) {
            return null;
        } else {
            return ((VirtualNode) parent).getTextRoot();
        }
    }


    private double getFontSizeFromContext() {
        if (fontSize != -1) {
            return fontSize;
        }
        GroupShadowNode root = getTextRoot();
        if (root == null) {
            return DEFAULT_FONT_SIZE;
        }

        if (glyphContext == null) {
            glyphContext = root.getGlyphContext();
        }

        fontSize = glyphContext.getFontSize();

        return fontSize;
    }

    abstract void draw(Canvas canvas, Paint paint, float opacity);
    void render(Canvas canvas, Paint paint, float opacity) {
        draw(canvas, paint, opacity);
    }

    /**
     * Sets up the transform matrix on the canvas before an element is drawn.
     *
     * NB: for perf reasons this does not apply opacity, as that would mean creating a new canvas
     * layer (which allocates an offscreen bitmap) and having it composited afterwards. Instead, the
     * drawing code should apply opacity recursively.
     *
     * @param canvas the canvas to set up
     */
    int saveAndSetupCanvas(Canvas canvas) {
        int count = canvas.save();
        canvas.concat(mMatrix);
        canvas.concat(mTransform);
        return count;
    }

    /**
     * Restore the canvas after an element was drawn. This is always called in mirror with
     * {@link #saveAndSetupCanvas}.
     *
     * @param canvas the canvas to restore
     */
    void restoreCanvas(Canvas canvas, int count) {
        canvas.restoreToCount(count);
    }

    @ReactProp(name = "name")
    public void setName(String name) {
        mName = name;
        markUpdated();
    }


    @ReactProp(name = "mask")
    public void setMask(String mask) {
        mMask = mask;
        markUpdated();
    }

    @ReactProp(name = "clipPath")
    public void setClipPath(String clipPath) {
        mCachedClipPath = null;
        mClipPath = clipPath;
        markUpdated();
    }

    @ReactProp(name = "clipRule", defaultInt = CLIP_RULE_NONZERO)
    public void setClipRule(int clipRule) {
        mClipRule = clipRule;
        markUpdated();
    }

    @ReactProp(name = "opacity", defaultFloat = 1f)
    public void setOpacity(float opacity) {
        mOpacity = opacity;
        markUpdated();
    }

    @ReactProp(name = "matrix")
    public void setMatrix(Dynamic matrixArray) {
        ReadableType type = matrixArray.getType();
        if (!matrixArray.isNull() && type.equals(ReadableType.Array)) {
            int matrixSize = PropHelper.toMatrixData(matrixArray.asArray(), sRawMatrix, mScale);
            if (matrixSize == 6) {
                if (mMatrix == null) {
                    mMatrix = new Matrix();
                    mInvMatrix = new Matrix();
                }
                mMatrix.setValues(sRawMatrix);
                mInvertible = mMatrix.invert(mInvMatrix);
            } else if (matrixSize != -1) {
                FLog.w(ReactConstants.TAG, "RNSVG: Transform matrices must be of size 6");
            }
        } else {
            mMatrix = null;
            mInvMatrix = null;
            mInvertible = false;
        }

        super.markUpdated();
    }

    @ReactProp(name = "responsible")
    public void setResponsible(boolean responsible) {
        mResponsible = responsible;
        markUpdated();
    }

    @Nullable Path getClipPath() {
        return mCachedClipPath;
    }

    @Nullable Path getClipPath(Canvas canvas, Paint paint) {
        if (mClipPath != null) {
            ClipPathShadowNode mClipNode = (ClipPathShadowNode) getSvgShadowNode().getDefinedClipPath(mClipPath);

            if (mClipNode != null) {
                Path clipPath = mClipNode.getPath(canvas, paint, Region.Op.UNION);
                switch (mClipRule) {
                    case CLIP_RULE_EVENODD:
                        clipPath.setFillType(Path.FillType.EVEN_ODD);
                        break;
                    case CLIP_RULE_NONZERO:
                        break;
                    default:
                        FLog.w(ReactConstants.TAG, "RNSVG: clipRule: " + mClipRule + " unrecognized");
                }
                mCachedClipPath = clipPath;
            } else {
                FLog.w(ReactConstants.TAG, "RNSVG: Undefined clipPath: " + mClipPath);
            }
        }

        return getClipPath();
    }

    void clip(Canvas canvas, Paint paint) {
        Path clip = getClipPath(canvas, paint);

        if (clip != null) {
            canvas.clipPath(clip);
        }
    }

    abstract int hitTest(final float[] point);

    boolean isResponsible() {
        return mResponsible;
    }

    abstract Path getPath(Canvas canvas, Paint paint);

    SvgViewShadowNode getSvgShadowNode() {
        if (mSvgShadowNode != null) {
            return mSvgShadowNode;
        }

        ReactShadowNode parent = getParent();

        if (parent == null) {
            return null;
        } else if (parent instanceof SvgViewShadowNode) {
            mSvgShadowNode = (SvgViewShadowNode)parent;
        } else if (parent instanceof VirtualNode) {
            mSvgShadowNode = ((VirtualNode) parent).getSvgShadowNode();
        } else {
            FLog.e(ReactConstants.TAG, "RNSVG: " + getClass().getName() + " should be descendant of a SvgViewShadow.");
        }

        return mSvgShadowNode;
    }

    double relativeOnWidth(String length) {
        return PropHelper.fromRelative(length, getCanvasWidth(), 0, mScale, getFontSizeFromContext());
    }

    double relativeOnHeight(String length) {
        return PropHelper.fromRelative(length, getCanvasHeight(), 0, mScale, getFontSizeFromContext());
    }

    double relativeOnOther(String length) {
        return PropHelper.fromRelative(length, getCanvasDiagonal(), 0, mScale, getFontSizeFromContext());
    }

    private float getCanvasWidth() {
        if (canvasWidth != -1) {
            return canvasWidth;
        }
        GroupShadowNode root = getTextRoot();
        if (root == null) {
            canvasWidth = getSvgShadowNode().getCanvasBounds().width();
        } else {
            canvasWidth = root.getGlyphContext().getWidth();
        }

        return canvasWidth;
    }

    private float getCanvasHeight() {
        if (canvasHeight != -1) {
            return canvasHeight;
        }
        GroupShadowNode root = getTextRoot();
        if (root == null) {
            canvasHeight = getSvgShadowNode().getCanvasBounds().height();
        } else {
            canvasHeight = root.getGlyphContext().getHeight();
        }

        return canvasHeight;
    }

    private double getCanvasDiagonal() {
        if (canvasDiagonal != -1) {
            return canvasDiagonal;
        }
        double powX = Math.pow((getCanvasWidth()), 2);
        double powY = Math.pow((getCanvasHeight()), 2);
        canvasDiagonal = Math.sqrt(powX + powY) * M_SQRT1_2l;
        return canvasDiagonal;
    }

    void saveDefinition() {
        if (mName != null) {
            getSvgShadowNode().defineTemplate(this, mName);
        }
    }

    interface NodeRunnable {
        void run(ReactShadowNode node);
    }

    void traverseChildren(NodeRunnable runner) {
        for (int i = 0; i < getChildCount(); i++) {
            ReactShadowNode child = getChildAt(i);
            runner.run(child);
        }
    }

    void setClientRect(RectF rect) {
        if (mClientRect != null && mClientRect.equals(rect)) {
            return;
        }
        mClientRect = rect;
        if (mClientRect == null) {
            return;
        }
        EventDispatcher eventDispatcher = this.getThemedContext()
                .getNativeModule(UIManagerModule.class)
                .getEventDispatcher();
        eventDispatcher.dispatchEvent(OnLayoutEvent.obtain(
                this.getReactTag(),
                (int) mClientRect.left,
                (int) mClientRect.top,
                (int) mClientRect.width(),
                (int) mClientRect.height()
        ));
    }

    RectF getClientRect() {
        return mClientRect;
    }

    String getStringFromDynamic(Dynamic dynamic) {
        switch (dynamic.getType()) {
            case String:
                return dynamic.asString();
            case Number:
                return String.valueOf(dynamic.asDouble());
            default:
                return null;
        }
    }

    ReadableArray getStringArrayFromDynamic(Dynamic dynamic) {
        switch (dynamic.getType()) {
            case Array:
                return dynamic.asArray();
            case String:
                return JavaOnlyArray.of(dynamic.asString());
            case Number:
                return JavaOnlyArray.of(String.valueOf(dynamic.asDouble()));
            default:
                return null;
        }
    }
}
