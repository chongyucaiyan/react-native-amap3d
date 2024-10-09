package qiuxiang.amap3d.map_view

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MyLocationStyle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import qiuxiang.amap3d.getFloat
import qiuxiang.amap3d.toJson
import qiuxiang.amap3d.toLatLng
import qiuxiang.amap3d.toPoint
import qiuxiang.amap3d.toPx

@SuppressLint("ViewConstructor")
class MapView(context: ThemedReactContext) : TextureMapView(context) {
  @Suppress("Deprecation")
  private val eventEmitter =
    context.getJSModule(com.facebook.react.uimanager.events.RCTEventEmitter::class.java)
  private val markerMap = HashMap<String, qiuxiang.amap3d.map_view.Marker>()
  private val polylineMap = HashMap<String, Polyline>()
  private var initialCameraPosition: ReadableMap? = null
  private var locationStyle: MyLocationStyle

  init {
    super.onCreate(null)

    locationStyle = MyLocationStyle()
    locationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
    locationStyle.radiusFillColor(Color.argb(0, 0, 0, 0))
    locationStyle.strokeColor(Color.argb(0, 0, 0, 0))
    map.myLocationStyle = locationStyle

    map.setOnMapLoadedListener {
      map.uiSettings.isCompassEnabled = false
      map.uiSettings.isScaleControlsEnabled = false
      map.uiSettings.isZoomControlsEnabled = false
      map.uiSettings.isRotateGesturesEnabled = false
      emit(id, "onLoad")
    }
    map.setOnMapClickListener { latLng -> emit(id, "onPress", latLng.toJson()) }
    map.setOnPOIClickListener { poi -> emit(id, "onPressPoi", poi.toJson()) }
    map.setOnMapLongClickListener { latLng -> emit(id, "onLongPress", latLng.toJson()) }
    map.setOnPolylineClickListener { polyline -> emit(polylineMap[polyline.id]?.id, "onPress") }

    map.setOnMarkerClickListener { marker ->
      markerMap[marker.id]?.let { emit(it.id, "onPress") }
      true
    }

    map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
      override fun onMarkerDragStart(marker: Marker) {
        emit(markerMap[marker.id]?.id, "onDragStart")
      }

      override fun onMarkerDrag(marker: Marker) {
        emit(markerMap[marker.id]?.id, "onDrag")
      }

      override fun onMarkerDragEnd(marker: Marker) {
        emit(markerMap[marker.id]?.id, "onDragEnd", marker.position.toJson())
      }
    })

    map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
      override fun onCameraChangeFinish(position: CameraPosition) {
        emit(id, "onCameraIdle", Arguments.createMap().apply {
          putMap("cameraPosition", position.toJson())
          putMap("latLngBounds", map.projection.visibleRegion.latLngBounds.toJson())
        })
      }

      override fun onCameraChange(position: CameraPosition) {
        emit(id, "onCameraMove", Arguments.createMap().apply {
          putMap("cameraPosition", position.toJson())
          putMap("latLngBounds", map.projection.visibleRegion.latLngBounds.toJson())
        })
      }
    })

    map.setOnMultiPointClickListener { item ->
      item.customerId.split("_").let {
        emit(
          it[0].toInt(),
          "onPress",
          Arguments.createMap().apply { putInt("index", it[1].toInt()) },
        )
      }
      false
    }

    map.setOnMyLocationChangeListener {
      if (it.time > 0) {
        emit(id, "onLocation", it.toJson())
      }
    }
  }

  fun emit(id: Int?, event: String, data: WritableMap = Arguments.createMap()) {
    @Suppress("Deprecation")
    id?.let { eventEmitter.receiveEvent(it, event, data) }
  }

  fun add(child: View) {
    if (child is Overlay) {
      child.add(map)
      if (child is qiuxiang.amap3d.map_view.Marker) {
        markerMap[child.marker?.id!!] = child
      }
      if (child is Polyline) {
        polylineMap[child.polyline?.id!!] = child
      }
    }
  }

  fun remove(child: View) {
    if (child is Overlay) {
      child.remove()
      if (child is qiuxiang.amap3d.map_view.Marker) {
        markerMap.remove(child.marker?.id)
      }
      if (child is Polyline) {
        polylineMap.remove(child.polyline?.id)
      }
    }
  }

  private val animateCallback = object : AMap.CancelableCallback {
    override fun onCancel() {}
    override fun onFinish() {}
  }

  fun includePoints(args: ReadableArray?) {
    val option = args?.getMap(0)

    val builder = LatLngBounds.Builder()
    val points = option?.getArray("points")
    val size = points?.size() ?: 0
    if (size > 0) {
      for (index in 0 until size) {
        val latLng = points?.getMap(index)
        if (latLng != null && latLng.hasKey("latitude") && latLng.hasKey("longitude")) {
          val latitude = latLng.getDouble("latitude")
          val longitude = latLng.getDouble("longitude")
          builder.include(LatLng(latitude, longitude))
        }
      }
    }

    val padding = option?.getArray("padding")
    val paddingLeft = (padding?.getDouble(3) ?: 0.0).toPx()
    val paddingRight = (padding?.getDouble(1) ?: 0.0).toPx()
    val paddingTop = (padding?.getDouble(0) ?: 0.0).toPx()
    val paddingBottom = (padding?.getDouble(2) ?: 0.0).toPx()

    val cameraUpdate = CameraUpdateFactory.newLatLngBoundsRect(builder.build(), paddingLeft, paddingRight, paddingTop, paddingBottom)
    map.animateCamera(cameraUpdate, animateCallback)
  }

  fun moveCamera(args: ReadableArray?) {
    val current = map.cameraPosition
    val position = args?.getMap(0)!!
    val target = position.getMap("target")?.toLatLng() ?: current.target
    val zoom = position.getFloat("zoom") ?: current.zoom
    val tilt = position.getFloat("tilt") ?: current.tilt
    val bearing = position.getFloat("bearing") ?: current.bearing
    val cameraUpdate = CameraUpdateFactory.newCameraPosition(
      CameraPosition(target, zoom, tilt, bearing)
    )
    map.animateCamera(cameraUpdate, args.getInt(1).toLong(), animateCallback)
  }

  fun setInitialCameraPosition(position: ReadableMap) {
    if (initialCameraPosition == null) {
      initialCameraPosition = position
      moveCamera(Arguments.createArray().apply {
        pushMap(Arguments.createMap().apply { merge(position) })
        pushInt(0)
      })
    }
  }

  fun call(args: ReadableArray?) {
    val id = args?.getDouble(0)!!
    when (args.getString(1)) {
      "getLatLng" -> callback(
        id,
        // @todo 暂时兼容 0.63
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        map.projection.fromScreenLocation(args.getMap(2)!!.toPoint()).toJson()
      )
    }
  }

  private fun callback(id: Double, data: Any) {
    emit(this.id, "onCallback", Arguments.createMap().apply {
      putDouble("id", id)
      when (data) {
        is WritableMap -> putMap("data", data)
      }
    })
  }
}
