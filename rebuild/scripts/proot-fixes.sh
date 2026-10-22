# Proot environment fixes applied to every Ubuntu session.
export MOZ_FAKE_NO_SANDBOX=1
export ELECTRON_DISABLE_SANDBOX=1
export GALLIUM_DRIVER=llvmpipe
export DISPLAY=:1
export PULSE_SERVER=tcp:127.0.0.1:4713
export QT_QPA_PLATFORM=xcb
export SSH_PORT=10022
unset GTK_IM_MODULE QT_IM_MODULE XMODIFIERS
