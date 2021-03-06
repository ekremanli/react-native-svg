/*
 * Copyright (c) 2015-present, Horcrux.
 * All rights reserved.
 *
 * This source code is licensed under the MIT-style license found in the
 * LICENSE file in the root directory of this source tree.
 */


package com.horcrux.svg;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.facebook.react.bridge.Dynamic;
import com.facebook.react.uimanager.annotations.ReactProp;

/**
 * Shadow node for virtual Rect view
 */
class RectShadowNode extends RenderableShadowNode {
    private String mX;
    private String mY;
    private String mW;
    private String mH;
    private String mRx;
    private String mRy;

    @ReactProp(name = "x")
    public void setX(Dynamic x) {
        mX = getStringFromDynamic(x);
        markUpdated();
    }

    @ReactProp(name = "y")
    public void setY(Dynamic y) {
        mY = getStringFromDynamic(y);
        markUpdated();
    }

    @ReactProp(name = "width")
    public void setWidth(Dynamic width) {
        mW = getStringFromDynamic(width);
        markUpdated();
    }

    @ReactProp(name = "height")
    public void setHeight(Dynamic height) {
        mH = getStringFromDynamic(height);
        markUpdated();
    }

    @ReactProp(name = "rx")
    public void setRx(Dynamic rx) {
        mRx = getStringFromDynamic(rx);
        markUpdated();
    }

    @ReactProp(name = "ry")
    public void setRy(Dynamic ry) {
        mRy = getStringFromDynamic(ry);
        markUpdated();
    }

    @Override
    Path getPath(Canvas canvas, Paint paint) {
        Path path = new Path();
        double x = relativeOnWidth(mX);
        double y = relativeOnHeight(mY);
        double w = relativeOnWidth(mW);
        double h = relativeOnHeight(mH);
        double rx = relativeOnWidth(mRx);
        double ry = relativeOnHeight(mRy);

        if (rx != 0 || ry != 0) {
            if (rx == 0) {
                rx = ry;
            } else if (ry == 0) {
                ry = rx;
            }

            if (rx > w / 2) {
                rx = w / 2;
            }

            if (ry > h / 2) {
                ry = h / 2;
            }
            path.addRoundRect(new RectF((float) x, (float) y, (float) (x + w), (float) (y + h)), (float) rx, (float) ry, Path.Direction.CW);
        } else {
            path.addRect((float) x, (float) y, (float) (x + w), (float) (y + h), Path.Direction.CW);
        }
        return path;
    }
}
