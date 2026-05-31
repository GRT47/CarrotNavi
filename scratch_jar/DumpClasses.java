
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
public class DumpClasses {
    public static void main(String[] args) throws Exception {
        URL[] urls = { new java.io.File("classes.jar").toURI().toURL() };
        URLClassLoader cl = new URLClassLoader(urls);
        String[] classes = {
            "com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment",
            "com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK"
        };
        for (String c : classes) {
            System.out.println("--- " + c + " ---");
            try {
                Class<?> clazz = cl.loadClass(c);
                for (Method m : clazz.getMethods()) {
                    System.out.println(m.getName());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

