/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wicketstuff.console.examples;

import org.apache.wicket.Page;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.Model;
import org.wicketstuff.console.examples.clojure.ClojureEngineTestPage;
import org.wicketstuff.console.examples.clojure.ClojureEngineWindowTestPage;
import org.wicketstuff.console.examples.clojure.ClojureEngineWithTemplatesTestPage;
import org.wicketstuff.console.examples.clojure.ClojureEngineWithTemplatesWindowTestPage;
import org.wicketstuff.console.examples.groovy.GroovyEngineTestPage;
import org.wicketstuff.console.examples.groovy.GroovyEngineWindowTestPage;
import org.wicketstuff.console.examples.groovy.GroovyEngineWithTemplatesTestPage;
import org.wicketstuff.console.examples.groovy.GroovyEngineWithTemplatesWindowTestPage;
import org.wicketstuff.console.examples.scala.ScalaEngineTestPage;
import org.wicketstuff.console.examples.scala.ScalaEngineWindowTestPage;
import org.wicketstuff.console.examples.scala.ScalaEngineWithTemplatesTestPage;
import org.wicketstuff.console.examples.scala.ScalaEngineWithTemplatesWindowTestPage;

public class TestPageLinksPanel extends Panel
{

	private static final long serialVersionUID = 1L;

	public TestPageLinksPanel(final String id)
	{
		super(id);

		final RepeatingView r = new RepeatingView("testPageLinks");
		add(r);

		addLink(r, GroovyEngineTestPage.class);
		addLink(r, GroovyEngineWindowTestPage.class);
		addLink(r, GroovyEngineWithTemplatesTestPage.class);
		addLink(r, GroovyEngineWithTemplatesWindowTestPage.class);

		addLink(r, ClojureEngineTestPage.class);
		addLink(r, ClojureEngineWindowTestPage.class);
		addLink(r, ClojureEngineWithTemplatesTestPage.class);
		addLink(r, ClojureEngineWithTemplatesWindowTestPage.class);

		addLink(r, ScalaEngineTestPage.class);
		addLink(r, ScalaEngineWindowTestPage.class);
		addLink(r, ScalaEngineWithTemplatesTestPage.class);
		addLink(r, ScalaEngineWithTemplatesWindowTestPage.class);

	}

	private void addLink(final RepeatingView r, final Class<? extends Page> pageClass)
	{
		final WebMarkupContainer c = new WebMarkupContainer(r.newChildId());
		final BookmarkablePageLink<String> link1 = new BookmarkablePageLink<String>("link",
			pageClass);
		link1.add(new Label("label", Model.of(pageClass.getSimpleName())));
		c.add(link1);
		r.add(c);
	}

}
