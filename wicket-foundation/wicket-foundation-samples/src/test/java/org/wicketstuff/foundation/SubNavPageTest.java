package org.wicketstuff.foundation;

import org.junit.jupiter.api.Test;
import org.wicketstuff.foundation.SubNavPage;

public class SubNavPageTest extends AbstractPageTest {

	@Test
	public void test() {
		tester.startPage(SubNavPage.class);
		tester.assertRenderedPage(SubNavPage.class);
	}
}
