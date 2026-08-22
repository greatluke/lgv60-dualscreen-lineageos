# LG Dual Screen (DS2) touchscreen.
#
# The DS2 presents its touch panel over USB HID as 1004:637a ("LGE LMV600N",
# INPUT_PROP_DIRECT with multitouch ABS axes). Android sees the device, but without this
# file it is not bound to the DS2's display, so touches are routed to the built-in screen
# (or dropped) and the second screen appears unresponsive.
#
# touch.displayId binds the device to a display by its unique id. The DS2 enumerates as
# DisplayDeviceInfo uniqueId="local:4" (port 4), reported as "HDMI Screen", 1080x2460.
#
# Verify with: dumpsys input | grep -A3 "LGE LMV600N"
# and look for AssociatedDisplay showing isExternal=true with a non-empty displayId.

touch.deviceType = touchScreen
touch.displayId = local:4
touch.orientationAware = 1
