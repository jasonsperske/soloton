package dev.soloton.roadwork

import com.badlogic.gdx.graphics.glutils.ShaderProgram

/** GLSL ES 1.00 shaders (OpenGL ES 2.0). All lighting and fog is per vertex; fragments only copy colour. */
internal object Shaders {
    /**
     * Flat-shaded scene geometry: hemisphere ambient + one low sun, emissive faces (colour alpha),
     * optional water sparkle and linear distance fog that ends in the sky's horizon colour.
     */
    const val SCENE_VERTEX = """
attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec4 a_color;
uniform mat4 u_projView;
uniform vec3 u_offset;
uniform vec3 u_camPos;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambSky;
uniform vec3 u_ambGround;
uniform vec3 u_fogColor;
uniform vec3 u_fog;   // start, 1 / (end - start), max amount
uniform float u_glow;
uniform float u_water;
varying vec3 v_color;
void main() {
    vec3 world = a_position + u_offset;
    vec3 n = a_normal;
    float diff = max(dot(n, u_sunDir), 0.0);
    vec3 hemi = mix(u_ambGround, u_ambSky, n.y * 0.5 + 0.5);
    vec3 lit = a_color.rgb * (hemi + u_sunColor * diff);
    vec3 toCam = u_camPos - world;
    float dist = length(toCam);
    if (u_water > 0.0) {
        vec3 v = toCam / max(dist, 0.001);
        vec3 r = reflect(-v, n);
        float spec = pow(max(dot(r, u_sunDir), 0.0), 60.0);
        float fres = pow(1.0 - max(dot(v, n), 0.0), 4.0);
        lit = mix(lit, u_fogColor * 0.85, fres * 0.55) + u_sunColor * spec * u_water;
    }
    lit = mix(lit, a_color.rgb * (0.3 + 0.9 * u_glow), a_color.a);
    float f = clamp((dist - u_fog.x) * u_fog.y, 0.0, u_fog.z);
    v_color = mix(lit, u_fogColor, f);
    gl_Position = u_projView * vec4(world, 1.0);
}
"""

    const val SCENE_FRAGMENT = """
#ifdef GL_ES
precision mediump float;
#endif
varying vec3 v_color;
void main() {
    gl_FragColor = vec4(v_color, 1.0);
}
"""

    /** Full-screen sky: vertical gradient from the horizon line plus a sun disc and glow. */
    const val SKY_VERTEX = """
attribute vec2 a_position;
void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
}
"""

    const val SKY_FRAGMENT = """
#ifdef GL_ES
precision mediump float;
#endif
uniform vec3 u_zenith;
uniform vec3 u_horizon;
uniform vec3 u_sunColor;
uniform vec4 u_geom;   // horizon y (px), 1 / height (px), sun x (px), sun y (px)
uniform float u_sun;   // 1 when the sun is in front of the camera
void main() {
    float y = (gl_FragCoord.y - u_geom.x) * u_geom.y;
    float t = clamp(y * 1.7, 0.0, 1.0);
    vec3 c = mix(u_horizon, u_zenith, sqrt(t));
    vec2 d = (gl_FragCoord.xy - u_geom.zw) * u_geom.y;
    float r = length(d);
    float glow = exp(-r * 4.0) * 0.40 + exp(-r * 15.0) * 0.30;
    c = min(c + u_sunColor * glow * u_sun, vec3(1.0));
    float disc = 1.0 - smoothstep(0.021, 0.025, r);
    c = mix(c, vec3(1.0), disc * u_sun);
    gl_FragColor = vec4(c, 1.0);
}
"""

    /** SpriteBatch shader that sharpens the scaled-up default bitmap font (alpha threshold, soft edge). */
    const val TEXT_VERTEX = """
attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
varying vec4 v_color;
varying vec2 v_uv;
void main() {
    v_color = a_color;
    v_color.a = v_color.a * (255.0 / 254.0);
    v_uv = a_texCoord0;
    gl_Position = u_projTrans * a_position;
}
"""

    const val TEXT_FRAGMENT = """
#ifdef GL_ES
precision mediump float;
#endif
varying vec4 v_color;
varying vec2 v_uv;
uniform sampler2D u_texture;
void main() {
    float a = texture2D(u_texture, v_uv).a;
    a = smoothstep(0.30, 0.62, a);
    gl_FragColor = vec4(v_color.rgb, v_color.a * a);
}
"""

    fun compile(vertex: String, fragment: String): ShaderProgram {
        ShaderProgram.pedantic = false
        val p = ShaderProgram(vertex.trimIndent(), fragment.trimIndent())
        check(p.isCompiled) { "shader failed to compile:\n${p.log}" }
        return p
    }
}
