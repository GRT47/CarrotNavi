package com.example.carrotnavi;

import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;
import androidx.databinding.DataBinderMapper;
import androidx.databinding.DataBindingComponent;
import androidx.databinding.ViewDataBinding;
import java.lang.Integer;
import java.lang.Object;
import java.lang.Override;
import java.lang.RuntimeException;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DataBinderMapperImpl extends DataBinderMapper {
  private static final SparseIntArray INTERNAL_LAYOUT_ID_LOOKUP = new SparseIntArray(0);

  @Override
  public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) {
    int localizedLayoutId = INTERNAL_LAYOUT_ID_LOOKUP.get(layoutId);
    if(localizedLayoutId > 0) {
      final Object tag = view.getTag();
      if(tag == null) {
        throw new RuntimeException("view must have a tag");
      }
    }
    return null;
  }

  @Override
  public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) {
    if(views == null || views.length == 0) {
      return null;
    }
    int localizedLayoutId = INTERNAL_LAYOUT_ID_LOOKUP.get(layoutId);
    if(localizedLayoutId > 0) {
      final Object tag = views[0].getTag();
      if(tag == null) {
        throw new RuntimeException("view must have a tag");
      }
      switch(localizedLayoutId) {
      }
    }
    return null;
  }

  @Override
  public int getLayoutId(String tag) {
    if (tag == null) {
      return 0;
    }
    Integer tmpVal = InnerLayoutIdLookup.sKeys.get(tag);
    return tmpVal == null ? 0 : tmpVal;
  }

  @Override
  public String convertBrIdToString(int localId) {
    String tmpVal = InnerBrLookup.sKeys.get(localId);
    return tmpVal;
  }

  @Override
  public List<DataBinderMapper> collectDependencies() {
    ArrayList<DataBinderMapper> result = new ArrayList<DataBinderMapper>(2);
    result.add(new androidx.databinding.library.baseAdapters.DataBinderMapperImpl());
    result.add(new com.tmapmobility.tmap.tmapsdk.ui.DataBinderMapperImpl());
    return result;
  }

  private static class InnerBrLookup {
    static final SparseArray<String> sKeys = new SparseArray<String>(65);

    static {
      sKeys.put(0, "_all");
      sKeys.put(1, "appInitComplete");
      sKeys.put(2, "arrivalTimeMode");
      sKeys.put(3, "body");
      sKeys.put(4, "bottomAddressMode");
      sKeys.put(5, "buttonClickListener");
      sKeys.put(6, "callback");
      sKeys.put(7, "centerFeeVisible");
      sKeys.put(8, "centerVisible");
      sKeys.put(9, "compassVisible");
      sKeys.put(10, "complexCrossroadVisible");
      sKeys.put(11, "count");
      sKeys.put(12, "countType");
      sKeys.put(13, "currentPositionVisible");
      sKeys.put(14, "data");
      sKeys.put(15, "departName");
      sKeys.put(16, "destName");
      sKeys.put(17, "drivingData");
      sKeys.put(18, "drivingMode");
      sKeys.put(19, "fuelButtonClickable");
      sKeys.put(20, "fuelButtonSelected");
      sKeys.put(21, "fuelButtonVisible");
      sKeys.put(22, "hasMoreHighwayItem");
      sKeys.put(23, "headerItemSize");
      sKeys.put(24, "highwayExitData");
      sKeys.put(25, "isAdViewVisible");
      sKeys.put(26, "isBottomInfoVisible");
      sKeys.put(27, "isCrossroadExpanded");
      sKeys.put(28, "isHighwayMiniMode");
      sKeys.put(29, "isNightMode");
      sKeys.put(30, "isOnHighway");
      sKeys.put(31, "isRerouteVisible");
      sKeys.put(32, "isStopVisible");
      sKeys.put(33, "isTbtVisible");
      sKeys.put(34, "laneViewOverlapped");
      sKeys.put(35, "leftButtonTitle");
      sKeys.put(36, "leftPocketExist");
      sKeys.put(37, "mapButtonMarginBottom");
      sKeys.put(38, "mapLayoutVisible");
      sKeys.put(39, "maxSafeLeft");
      sKeys.put(40, "movedProgress");
      sKeys.put(41, "naviCurrentPositionVisible");
      sKeys.put(42, "naviViewVisible");
      sKeys.put(43, "naviZoomVisible");
      sKeys.put(44, "navigationVisible");
      sKeys.put(45, "nearViaPoint");
      sKeys.put(46, "orientation");
      sKeys.put(47, "previewBtnVisible");
      sKeys.put(48, "previewHeaderVisible");
      sKeys.put(49, "progressVisible");
      sKeys.put(50, "rightButtonTitle");
      sKeys.put(51, "rightPocketExist");
      sKeys.put(52, "rotationAngle");
      sKeys.put(53, "routeOption");
      sKeys.put(54, "routeSummaryInfo");
      sKeys.put(55, "serviceAreaData");
      sKeys.put(56, "simulationRepeatOnce");
      sKeys.put(57, "summaryViewVisible");
      sKeys.put(58, "tbtOrientation");
      sKeys.put(59, "tiltAngle");
      sKeys.put(60, "tollFee");
      sKeys.put(61, "totalDistance");
      sKeys.put(62, "uiMode");
      sKeys.put(63, "viaDataSize");
      sKeys.put(64, "viewMode");
    }
  }

  private static class InnerLayoutIdLookup {
    static final HashMap<String, Integer> sKeys = new HashMap<String, Integer>(0);
  }
}
