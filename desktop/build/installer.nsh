; PhoneMirror NSIS 安装脚本扩展
; 用于自定义安装过程

!macro customInit
  ; 初始化时检查是否已安装
  ReadRegStr $0 HKCU "Software\PhoneMirror" "InstallPath"
  ${If} $0 != ""
    MessageBox MB_YESNO "检测到已安装 PhoneMirror，是否先卸载旧版本？" IDYES uninstall IDNO continue
    uninstall:
      ExecWait '"$0\Uninstall PhoneMirror.exe" /S'
      Sleep 2000
    continue:
  ${EndIf}
!macroend

!macro customInstall
  ; 安装完成后创建防火墙规则
  DetailPrint "正在配置 Windows 防火墙..."
  nsExec::Exec 'netsh advfirewall firewall add rule name="PhoneMirror" dir=in action=allow program="$INSTDIR\PhoneMirror.exe" enable=yes'
  
  ; 注册协议处理程序 (phonemirror://)
  WriteRegStr HKCU "Software\Classes\phonemirror" "" "URL:PhoneMirror Protocol"
  WriteRegStr HKCU "Software\Classes\phonemirror" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\phonemirror\shell\open\command" "" '"$INSTDIR\PhoneMirror.exe" "%1"'
!macroend

!macro customUnInstall
  ; 卸载时删除防火墙规则
  DetailPrint "正在移除 Windows 防火墙规则..."
  nsExec::Exec 'netsh advfirewall firewall delete rule name="PhoneMirror"'
  
  ; 删除协议处理程序
  DeleteRegKey HKCU "Software\Classes\phonemirror"
!macroend
