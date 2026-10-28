import { useEffect, useMemo, useRef, useState } from 'react';
import * as THREE from 'three';
import { geoArea, geoBounds, geoCentroid, geoContains, geoEquirectangular, geoPath } from 'd3-geo';
import './Globe.css';

const RADIUS = 1.65;
const RADIANS = Math.PI / 180;
const INITIAL_PITCH = 21 * RADIANS;
const INITIAL_YAW = -19 * RADIANS;
const TWO_PI = Math.PI * 2;
const MAX_PITCH = 1.38;
const clampZoom = (value) => THREE.MathUtils.clamp(value, 0.76, 2.65);

function pointOnSphere([longitude, latitude], radius = RADIUS) {
  const phi = longitude * RADIANS;
  const theta = latitude * RADIANS;
  return new THREE.Vector3(
    radius * Math.cos(theta) * Math.sin(phi),
    radius * Math.sin(theta),
    radius * Math.cos(theta) * Math.cos(phi),
  );
}

function countryCode(feature) {
  return String(feature.properties?.iso_a2 || '').toUpperCase();
}

// Natural Earth is usually already D3-compatible; accept RFC ring winding too.
function normalizeFeature(feature) {
  const normalizePolygon = (coordinates) => {
    const polygon = { type: 'Polygon', coordinates };
    return geoArea(polygon) > TWO_PI ? coordinates.map((ring) => [...ring].reverse()) : coordinates;
  };
  const geometry = feature.geometry;
  if (geometry.type === 'Polygon') {
    return { ...feature, geometry: { ...geometry, coordinates: normalizePolygon(geometry.coordinates) } };
  }
  return {
    ...feature,
    geometry: { ...geometry, coordinates: geometry.coordinates.map(normalizePolygon) },
  };
}

function countryRings(feature) {
  return feature.geometry.type === 'Polygon'
    ? feature.geometry.coordinates
    : feature.geometry.coordinates.flat();
}

function borderGeometry(features, radius) {
  const positions = [];
  const progress = [];
  for (const feature of features) {
    for (const ring of countryRings(feature)) {
      const points = [];
      for (let index = 1; index < ring.length; index += 1) {
        const start = pointOnSphere(ring[index - 1], 1);
        const end = pointOnSphere(ring[index], 1);
        const steps = Math.max(1, Math.ceil(start.angleTo(end) / 0.018));
        for (let step = 0; step < steps; step += 1) {
          points.push(start.clone().lerp(end, step / steps).normalize().multiplyScalar(radius));
        }
      }
      if (ring.length) points.push(pointOnSphere(ring[ring.length - 1], radius));
      const lengths = [0];
      for (let index = 1; index < points.length; index += 1) {
        lengths.push(lengths[index - 1] + points[index].distanceTo(points[index - 1]));
      }
      const total = lengths[lengths.length - 1] || 1;
      for (let index = 1; index < points.length; index += 1) {
        positions.push(...points[index - 1].toArray(), ...points[index].toArray());
        progress.push(lengths[index - 1] / total, lengths[index] / total);
      }
    }
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  geometry.setAttribute('aProgress', new THREE.Float32BufferAttribute(progress, 1));
  return geometry;
}

function pulseMaterial(active) {
  return new THREE.ShaderMaterial({
    uniforms: { uTime: { value: 0 }, uActive: { value: active ? 1 : 0 }, uMotion: { value: 1 } },
    vertexShader: `
      attribute float aProgress;
      varying float vProgress;
      void main() {
        vProgress = aProgress;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: `
      uniform float uTime;
      uniform float uActive;
      uniform float uMotion;
      varying float vProgress;
      void main() {
        float position = fract(vProgress - uTime * 0.24);
        float trail = pow(1.0 - position, 7.0) * uMotion;
        float pulse = 0.82 + 0.18 * sin(uTime * 5.5) * uMotion;
        vec3 color = mix(vec3(0.65, 0.12, 0.16), vec3(1.0, 0.42, 0.37), trail);
        float opacity = mix(0.70, 0.72 + trail * 0.28, uActive) * pulse;
        gl_FragColor = vec4(color, opacity);
      }
    `,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    toneMapped: false,
  });
}

function mapCanvas() {
  const canvas = document.createElement('canvas');
  canvas.width = 2048;
  canvas.height = 1024;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas unavailable');
  const projection = geoEquirectangular()
    .translate([canvas.width / 2, canvas.height / 2])
    .scale(canvas.width / TWO_PI)
    .precision(0.3);
  return { canvas, context, path: geoPath(projection, context) };
}

function textureFromCanvas(canvas) {
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.wrapS = THREE.RepeatWrapping;
  // SphereGeometry's native seam is at -90°, while the map seam is at -180°.
  texture.offset.x = 0.25;
  texture.anisotropy = 4;
  return texture;
}

function boundsContain(bounds, longitude, latitude) {
  const [[west, south], [east, north]] = bounds;
  return latitude >= south && latitude <= north
    && (west <= east ? longitude >= west && longitude <= east : longitude >= west || longitude <= east);
}

export default function Globe({
  countries = [],
  selectedCode = null,
  hoveredCode,
  onHover,
  onSelect,
  zoomSignal,
  resetSignal,
  reducedMotion = false,
}) {
  const hostRef = useRef(null);
  const controllerRef = useRef(null);
  const [status, setStatus] = useState('loading');
  const countriesByCode = useMemo(() => new Map(countries.map((country) => [country.isoCode, country])), [countries]);
  const liveRef = useRef({});
  liveRef.current = { countriesByCode, selectedCode, hoveredCode, onHover, onSelect, reducedMotion };

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return undefined;
    let renderer;
    let disposed = false;
    let animationFrame = 0;
    let ready = false;
    let features = [];
    let featureByCode = new Map();
    let focusedBorder = null;
    let selectedBorder = null;
    let hoverCode = null;
    let pointerInside = false;
    let targetPitch = INITIAL_PITCH;
    let targetYaw = INITIAL_YAW;
    let zoom = 1;
    let fitDistance = 5.9;
    let pendingPointer = null;
    let drag = null;
    let pinch = null;
    let lastTime = performance.now();
    const pointers = new Map();
    const abortController = new AbortController();
    const resources = new Set();
    const remember = (resource) => { resources.add(resource); return resource; };

    try {
      renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'high-performance' });
    } catch {
      setStatus('unavailable');
      return undefined;
    }

    renderer.setClearColor(0x000000, 0);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.8));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    const canvas = renderer.domElement;
    canvas.className = 'earth-canvas';
    canvas.tabIndex = 0;
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', 'Etkileşimli dünya küresi. Döndürmek için sürükleyin veya yön tuşlarını, yakınlaştırmak için tekerleği veya artı ve eksi tuşlarını kullanın.');
    host.appendChild(canvas);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(36, 1, 0.1, 30);
    camera.position.z = fitDistance;
    const earth = new THREE.Group();
    earth.rotation.order = 'XYZ';
    earth.rotation.set(INITIAL_PITCH, INITIAL_YAW, 0);
    scene.add(earth);
    scene.add(new THREE.AmbientLight(0xffffff, 1.38));
    const key = new THREE.DirectionalLight(0xffffff, 2.15);
    key.position.set(-4, 5, 7);
    scene.add(key);
    const fill = new THREE.DirectionalLight(0x9c9eaa, 0.42);
    fill.position.set(5, -2, 1);
    scene.add(fill);

    const baseMap = mapCanvas();
    baseMap.context.fillStyle = '#111214';
    baseMap.context.fillRect(0, 0, baseMap.canvas.width, baseMap.canvas.height);
    const surfaceTexture = remember(textureFromCanvas(baseMap.canvas));
    const surface = new THREE.Mesh(
      remember(new THREE.SphereGeometry(RADIUS, 128, 96)),
      remember(new THREE.MeshPhongMaterial({ map: surfaceTexture, shininess: 3, specular: 0x111111 })),
    );
    earth.add(surface);

    const highlightMap = mapCanvas();
    const highlightTexture = remember(textureFromCanvas(highlightMap.canvas));
    const highlight = new THREE.Mesh(
      remember(new THREE.SphereGeometry(RADIUS * 1.002, 128, 96)),
      remember(new THREE.MeshBasicMaterial({ map: highlightTexture, transparent: true, opacity: 0.34, depthWrite: false })),
    );
    earth.add(highlight);

    const glowMaterial = remember(new THREE.ShaderMaterial({
      uniforms: {},
      vertexShader: `varying vec3 vNormal; varying vec3 vPosition;
        void main(){ vec4 pos = modelViewMatrix * vec4(position,1.0); vPosition=pos.xyz;
          vNormal=normalize(normalMatrix * normal); gl_Position=projectionMatrix * pos; }`,
      fragmentShader: `varying vec3 vNormal; varying vec3 vPosition;
        void main(){ float rim=pow(1.0-abs(dot(normalize(vNormal),normalize(-vPosition))),3.8);
          gl_FragColor=vec4(vec3(0.68,0.69,0.72),rim*0.13); }`,
      transparent: true,
      side: THREE.BackSide,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }));
    earth.add(new THREE.Mesh(remember(new THREE.SphereGeometry(RADIUS * 1.025, 80, 64)), glowMaterial));

    const hoverMaterial = remember(pulseMaterial(true));
    const selectedMaterial = remember(pulseMaterial(false));
    const raycaster = new THREE.Raycaster();
    const normalizedPointer = new THREE.Vector2();
    const worldPoint = new THREE.Vector3();

    function clearLine(line) {
      if (!line) return;
      earth.remove(line);
      resources.delete(line.geometry);
      line.geometry.dispose();
    }

    function updateHighlight() {
      const ctx = highlightMap.context;
      ctx.clearRect(0, 0, highlightMap.canvas.width, highlightMap.canvas.height);
      const selected = featureByCode.get(liveRef.current.selectedCode);
      const hovered = featureByCode.get(hoverCode);
      if (selected) {
        ctx.beginPath();
        highlightMap.path(selected);
        ctx.fillStyle = '#d45559';
        ctx.fill();
      }
      if (hovered && hovered !== selected) {
        ctx.beginPath();
        highlightMap.path(hovered);
        ctx.fillStyle = '#ffffff';
        ctx.fill();
      }
      highlightTexture.needsUpdate = true;
    }

    function setHovered(code) {
      if (hoverCode === code) return;
      hoverCode = code;
      clearLine(focusedBorder);
      focusedBorder = null;
      const feature = featureByCode.get(code);
      if (feature) {
        focusedBorder = new THREE.LineSegments(remember(borderGeometry([feature], RADIUS * 1.005)), hoverMaterial);
        focusedBorder.renderOrder = 3;
        earth.add(focusedBorder);
      }
      updateHighlight();
    }

    function clearHover() {
      pendingPointer = null;
      setHovered(null);
      canvas.style.cursor = 'grab';
      liveRef.current.onHover?.(null);
    }

    function countryAtPointer(event) {
      if (!ready) return null;
      const rect = canvas.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) return null;
      normalizedPointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
      earth.updateMatrixWorld(true);
      camera.updateMatrixWorld(true);
      raycaster.setFromCamera(normalizedPointer, camera);
      const hit = raycaster.intersectObject(surface, false)[0];
      if (!hit) return null;
      worldPoint.copy(hit.point);
      earth.worldToLocal(worldPoint).normalize();
      const longitude = Math.atan2(worldPoint.x, worldPoint.z) / RADIANS;
      const latitude = Math.asin(THREE.MathUtils.clamp(worldPoint.y, -1, 1)) / RADIANS;
      const feature = features.find((candidate) =>
        liveRef.current.countriesByCode.has(countryCode(candidate))
        && boundsContain(candidate.bounds, longitude, latitude)
        && geoContains(candidate, [longitude, latitude]));
      return feature ? liveRef.current.countriesByCode.get(countryCode(feature)) : null;
    }

    function sendHover(event) {
      const country = countryAtPointer(event);
      const code = country?.isoCode ?? null;
      setHovered(code);
      canvas.style.cursor = country ? 'pointer' : 'grab';
      liveRef.current.onHover?.(country ? { code, name: country.name, x: event.clientX, y: event.clientY } : null);
    }

    function focusCountry(code) {
      clearHover();
      clearLine(selectedBorder);
      selectedBorder = null;
      const feature = featureByCode.get(code);
      if (feature) {
        selectedBorder = new THREE.LineSegments(remember(borderGeometry([feature], RADIUS * 1.004)), selectedMaterial);
        selectedBorder.renderOrder = 2;
        earth.add(selectedBorder);
        const label = [Number(feature.properties.label_lon), Number(feature.properties.label_lat)];
        const [longitude, latitude] = label.every(Number.isFinite) ? label : geoCentroid(feature);
        if (Number.isFinite(longitude) && Number.isFinite(latitude)) {
          const nextYaw = -longitude * RADIANS;
          targetYaw = earth.rotation.y + THREE.MathUtils.euclideanModulo(nextYaw - earth.rotation.y + Math.PI, TWO_PI) - Math.PI;
          targetPitch = THREE.MathUtils.clamp(latitude * RADIANS, -MAX_PITCH, MAX_PITCH);
          zoom = Math.max(zoom, 1.05);
        }
      }
      updateHighlight();
    }

    function reset() {
      clearHover();
      targetPitch = INITIAL_PITCH;
      targetYaw = earth.rotation.y + THREE.MathUtils.euclideanModulo(INITIAL_YAW - earth.rotation.y + Math.PI, TWO_PI) - Math.PI;
      zoom = 1;
    }

    const controller = {
      focusCountry,
      setHovered,
      zoom: (direction) => {
        if (direction !== 1 && direction !== -1) return;
        clearHover();
        zoom = clampZoom(zoom * (direction === 1 ? 1.22 : 1 / 1.22));
      },
      reset,
    };
    controllerRef.current = controller;

    function onPointerDown(event) {
      if (event.button !== 0 && event.pointerType === 'mouse') return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      canvas.setPointerCapture(event.pointerId);
      if (pointers.size === 1) {
        drag = { x: event.clientX, y: event.clientY, yaw: targetYaw, pitch: targetPitch, moved: false };
      } else if (pointers.size === 2) {
        const [a, b] = [...pointers.values()];
        pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), zoom };
        if (drag) drag.moved = true;
      }
      canvas.classList.add('is-dragging');
      clearHover();
    }

    function onPointerMove(event) {
      if (pointers.has(event.pointerId)) {
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pointers.size >= 2 && pinch) {
          const [a, b] = [...pointers.values()];
          zoom = clampZoom(pinch.zoom * Math.hypot(a.x - b.x, a.y - b.y) / Math.max(1, pinch.distance));
        } else if (drag && !pinch) {
          const dx = event.clientX - drag.x;
          const dy = event.clientY - drag.y;
          if (Math.hypot(dx, dy) > 5) drag.moved = true;
          targetYaw = drag.yaw + dx * 0.0045;
          targetPitch = THREE.MathUtils.clamp(drag.pitch + dy * 0.0045, -MAX_PITCH, MAX_PITCH);
        }
        return;
      }
      pendingPointer = { clientX: event.clientX, clientY: event.clientY };
    }

    function onPointerUp(event) {
      const wasClick = pointers.size === 1 && drag && !drag.moved && !pinch;
      pointers.delete(event.pointerId);
      if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
      if (wasClick) {
        const country = countryAtPointer(event);
        if (country) {
          clearHover();
          liveRef.current.onSelect?.(country.isoCode);
        }
      }
      rebaseGesture();
    }

    function rebaseGesture() {
      if (pointers.size >= 2) {
        const [a, b] = [...pointers.values()];
        pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), zoom };
        if (drag) drag.moved = true;
      } else if (pointers.size === 1) {
        const [remaining] = pointers.values();
        // Continue rotating immediately after one finger leaves a pinch.
        // Mark the gesture as moved so lifting the final finger cannot select a country.
        pinch = null;
        drag = { x: remaining.x, y: remaining.y, yaw: targetYaw, pitch: targetPitch, moved: true };
      } else {
        drag = null;
        pinch = null;
        canvas.classList.remove('is-dragging');
      }
    }

    function onPointerCancel(event) {
      pointers.delete(event.pointerId);
      if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
      rebaseGesture();
    }

    function onPointerLeave() {
      pointerInside = false;
      pendingPointer = null;
      if (!drag) clearHover();
    }

    function onPointerEnter() {
      pointerInside = true;
    }

    function onWheel(event) {
      event.preventDefault();
      clearHover();
      zoom = clampZoom(zoom * Math.exp(-event.deltaY * 0.00115));
    }

    function onKeyDown(event) {
      const arrows = { ArrowLeft: [-0.13, 0], ArrowRight: [0.13, 0], ArrowUp: [0, -0.1], ArrowDown: [0, 0.1] };
      if (arrows[event.key]) {
        event.preventDefault();
        clearHover();
        targetYaw += arrows[event.key][0];
        targetPitch = THREE.MathUtils.clamp(targetPitch + arrows[event.key][1], -MAX_PITCH, MAX_PITCH);
      } else if (['+', '=', '-', '_'].includes(event.key)) {
        event.preventDefault();
        controller.zoom(event.key === '+' || event.key === '=' ? 1 : -1);
      } else if (event.key === 'Home') {
        event.preventDefault();
        reset();
      }
    }

    function onContextLost(event) {
      event.preventDefault();
      setStatus('unavailable');
    }

    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', onPointerUp);
    canvas.addEventListener('pointercancel', onPointerCancel);
    canvas.addEventListener('pointerleave', onPointerLeave);
    canvas.addEventListener('pointerenter', onPointerEnter);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('keydown', onKeyDown);
    canvas.addEventListener('webglcontextlost', onContextLost);

    function resize() {
      const { width, height } = host.getBoundingClientRect();
      if (!width || !height) return;
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      fitDistance = RADIUS / Math.sin((camera.fov * RADIANS / 2) * Math.min(1, camera.aspect)) * 1.1;
      if (ready && hoverCode) clearHover();
      renderer.setSize(width, height);
    }
    const observer = new ResizeObserver(resize);
    observer.observe(host);
    resize();

    async function loadCountries() {
      try {
        const response = await fetch('/data/countries.geojson', { signal: abortController.signal });
        if (!response.ok) throw new Error('Country map unavailable');
        const collection = await response.json();
        if (disposed) return;
        features = collection.features
          .filter((feature) => ['Polygon', 'MultiPolygon'].includes(feature.geometry?.type))
          .map(normalizeFeature)
          .map((feature) => ({ ...feature, bounds: geoBounds(feature), area: geoArea(feature) }))
          .sort((a, b) => a.area - b.area);
        featureByCode = new Map(features.map((feature) => [countryCode(feature), feature]));
        const colors = ['#777b78', '#8c8e88', '#858882', '#989a93', '#7e817b'];
        for (const feature of features) {
          const code = countryCode(feature);
          const index = [...code].reduce((sum, char) => sum + char.charCodeAt(0), 0) % colors.length;
          baseMap.context.beginPath();
          baseMap.path(feature);
          baseMap.context.fillStyle = colors[index];
          baseMap.context.fill();
        }
        surfaceTexture.needsUpdate = true;
        const borders = new THREE.LineSegments(
          remember(borderGeometry(features, RADIUS * 1.003)),
          remember(new THREE.LineBasicMaterial({ color: 0x844044, transparent: true, opacity: 0.5, depthWrite: false })),
        );
        earth.add(borders);
        ready = true;
        focusCountry(liveRef.current.selectedCode);
        if (liveRef.current.hoveredCode) setHovered(liveRef.current.hoveredCode);
        setStatus('ready');
      } catch (error) {
        if (!disposed && error.name !== 'AbortError') setStatus('unavailable');
      }
    }
    loadCountries();

    function render(now) {
      if (disposed) return;
      const delta = Math.min((now - lastTime) / 1000, 0.05);
      lastTime = now;
      const reduced = liveRef.current.reducedMotion;
      if (!reduced && ready && !drag && !pointerInside && !hoverCode && !liveRef.current.selectedCode) targetYaw += delta * 0.027;
      const smoothing = reduced ? 1 : 1 - Math.exp(-delta * 6.5);
      earth.rotation.x = THREE.MathUtils.lerp(earth.rotation.x, targetPitch, smoothing);
      earth.rotation.y = THREE.MathUtils.lerp(earth.rotation.y, targetYaw, smoothing);
      camera.position.z = THREE.MathUtils.lerp(camera.position.z, Math.max(RADIUS * 1.3, fitDistance / zoom), smoothing);
      hoverMaterial.uniforms.uTime.value = now / 1000;
      selectedMaterial.uniforms.uTime.value = now / 1000;
      hoverMaterial.uniforms.uMotion.value = reduced ? 0 : 1;
      selectedMaterial.uniforms.uMotion.value = 0;
      if (pendingPointer && !drag) {
        sendHover(pendingPointer);
        pendingPointer = null;
      }
      renderer.render(scene, camera);
      animationFrame = requestAnimationFrame(render);
    }
    animationFrame = requestAnimationFrame(render);

    return () => {
      disposed = true;
      abortController.abort();
      cancelAnimationFrame(animationFrame);
      observer.disconnect();
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointermove', onPointerMove);
      canvas.removeEventListener('pointerup', onPointerUp);
      canvas.removeEventListener('pointercancel', onPointerCancel);
      canvas.removeEventListener('pointerleave', onPointerLeave);
      canvas.removeEventListener('pointerenter', onPointerEnter);
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('keydown', onKeyDown);
      canvas.removeEventListener('webglcontextlost', onContextLost);
      resources.forEach((resource) => resource.dispose());
      renderer.dispose();
      canvas.remove();
      if (controllerRef.current === controller) controllerRef.current = null;
    };
  }, []);

  useEffect(() => { controllerRef.current?.focusCountry(selectedCode); }, [selectedCode]);
  useEffect(() => {
    if (hoveredCode !== undefined) controllerRef.current?.setHovered(hoveredCode);
  }, [hoveredCode]);
  useEffect(() => {
    if (zoomSignal?.id > 0 && (zoomSignal.direction === 1 || zoomSignal.direction === -1)) {
      controllerRef.current?.zoom(zoomSignal.direction);
    }
  }, [zoomSignal]);
  useEffect(() => {
    if (resetSignal) controllerRef.current?.reset();
  }, [resetSignal]);

  return (
    <div className={`globe-renderer globe-renderer--${status}`} ref={hostRef}>
      {status === 'loading' && <div className="globe-message" role="status"><span className="globe-loader" /><span>Dünya hazırlanıyor</span></div>}
      {status === 'unavailable' && <div className="globe-message globe-message--error" role="status"><span className="globe-fallback-orbit" /><strong>Küre görüntülenemedi</strong><span>Ülkeleri arama alanından keşfetmeye devam edebilirsin.</span></div>}
    </div>
  );
}
