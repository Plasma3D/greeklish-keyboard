using System.Runtime.InteropServices;

namespace GreeklishKeyboard;

/// <summary>
/// Asks Windows UI Automation whether the focused text box is a password box, so the app
/// stays out of passwords in every program (including browsers). Minimal COM declarations
/// instead of the WPF automation library, which keeps the .exe much smaller.
/// </summary>
internal static class Accessibility
{
    private const int UIA_IsPasswordPropertyId = 30019;
    private static IUIAutomation? _uia;

    public static bool FocusedIsPassword()
    {
        _uia ??= (IUIAutomation)new CUIAutomation();
        var el = _uia.GetFocusedElement();
        if (el == null) return false;
        try
        {
            return el.GetCurrentPropertyValue(UIA_IsPasswordPropertyId) is true;
        }
        finally
        {
            Marshal.ReleaseComObject(el);
        }
    }

    [ComImport, Guid("ff48dba4-60ef-4201-aa87-54103eef594e")]
    private class CUIAutomation { }

    // Only the methods we call matter; the placeholders keep the vtable order right.
    [ComImport, Guid("30cbe57d-d9d0-452a-ab13-7ac5ac4825ee"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IUIAutomation
    {
        void CompareElements();
        void CompareRuntimeIds();
        void GetRootElement();
        void ElementFromHandle();
        void ElementFromPoint();
        IUIAutomationElement GetFocusedElement();
    }

    [ComImport, Guid("d22108aa-8ac5-49a5-837b-37bbb3d7591e"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IUIAutomationElement
    {
        void SetFocus();
        void GetRuntimeId();
        void FindFirst();
        void FindAll();
        void FindFirstBuildCache();
        void FindAllBuildCache();
        void BuildUpdatedCache();
        [return: MarshalAs(UnmanagedType.Struct)]
        object GetCurrentPropertyValue(int propertyId);
    }
}
