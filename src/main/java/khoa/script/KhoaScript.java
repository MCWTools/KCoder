package khoa.script;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KhoaScript implements ModInitializer {
	public static final String MOD_ID = "khoascript";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Everything lives on the client side.
	}
}
