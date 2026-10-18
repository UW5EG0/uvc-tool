precision mediump float;

// Texture holds YUYV packed as RGBA: one texel = two horizontal pixels
//   texel.r = Y0   texel.g = U   texel.b = Y1   texel.a = V
uniform sampler2D u_texture;
uniform vec2  u_texSize;    // (frameWidth / 2, frameHeight)

uniform bool  u_flipX;      // mirror horizontally (side-view mirror attachment)
uniform int   u_rotation;   // 0, 90, 180, 270 — clockwise
uniform vec3  u_wbGain;     // white balance multipliers (R, G, B)
uniform float u_gamma;      // 1.0 = linear

varying vec2 v_texCoord;    // (0,0) top-left .. (1,1) bottom-right of the quad

// BT.601 limited range -> RGB
vec3 yuv2rgb(float y, float u, float v) {
    float yy = (y - 16.0 / 255.0) * (255.0 / 219.0);
    float uu = (u - 128.0 / 255.0) * (255.0 / 224.0);
    float vv = (v - 128.0 / 255.0) * (255.0 / 224.0);
    return clamp(vec3(
        yy + 1.402    * vv,
        yy - 0.344136 * uu - 0.714136 * vv,
        yy + 1.772    * uu), 0.0, 1.0);
}

void main() {
    vec2 uv = v_texCoord;

    // Rotation of the (square) quad around its centre. The viewport already
    // carries the correct aspect for the rotated frame, so this is distortion-free.
    vec2 d = uv - 0.5;
    if (u_rotation == 90) {
        d = vec2(d.y, -d.x);
    } else if (u_rotation == 180) {
        d = -d;
    } else if (u_rotation == 270) {
        d = vec2(-d.y, d.x);
    }
    uv = d + 0.5;

    if (u_flipX) uv.x = 1.0 - uv.x;

    // Pixel index across the full frame width; texel = pair of pixels.
    float px     = uv.x * u_texSize.x * 2.0;
    float texelX = (floor(px * 0.5) + 0.5) / u_texSize.x;     // centre of the texel
    float texelY = (floor(uv.y * u_texSize.y) + 0.5) / u_texSize.y;
    bool  odd    = mod(px, 2.0) >= 1.0;

    vec4 t = texture2D(u_texture, vec2(texelX, texelY));
    float y = odd ? t.b : t.r;

    vec3 rgb = yuv2rgb(y, t.g, t.a);
    rgb = clamp(rgb * u_wbGain, 0.0, 1.0);
    if (u_gamma != 1.0) rgb = pow(rgb, vec3(1.0 / u_gamma));

    gl_FragColor = vec4(rgb, 1.0);
}
