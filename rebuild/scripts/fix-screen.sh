#!/data/data/com.termux/files/usr/bin/bash
set -e

termux-x11-preference \
    adjustHeightForEK:true \
    enableAccessibilityServiceAutomatically:true \
    additionalKbdVisible:true

# Uncomment only for the source device resolution:
# termux-x11-preference displayResolutionMode:exact displayResolutionExact:1280x2260
