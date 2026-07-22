#pragma once

namespace ntk::present {

/**
 * Converts a top-left Android viewport Y coordinate to the NDC coordinate used while rendering
 * directly into an AHardwareBuffer that SurfaceControl will composite.
 *
 * The first AHardwareBuffer row is the compositor's top row, but OpenGL writes that row at
 * framebuffer Y=0 / NDC=-1. A normal window-surface projection (+1 at the visual top) therefore
 * flips both page order and every bitmap vertically when reused for this off-screen target.
 */
constexpr float ahbCompositorNdcY(float topLeftY, float viewportHeight) noexcept {
    return viewportHeight > 0.0F
        ? -1.0F + 2.0F * topLeftY / viewportHeight
        : -1.0F;
}

static_assert(ahbCompositorNdcY(0.0F, 100.0F) == -1.0F);
static_assert(ahbCompositorNdcY(50.0F, 100.0F) == 0.0F);
static_assert(ahbCompositorNdcY(100.0F, 100.0F) == 1.0F);

}  // namespace ntk::present
