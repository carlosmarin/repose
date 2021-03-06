package com.rackspace.papi.components.translation.xslt.xmlfilterchain;

import com.rackspace.papi.components.translation.httpx.HttpxMarshaller;
import com.rackspace.papi.components.translation.resolvers.ClassPathUriResolver;
import com.rackspace.papi.components.translation.resolvers.HttpxUriInputParameterResolver;
import com.rackspace.papi.components.translation.resolvers.InputStreamUriParameterResolver;
import com.rackspace.papi.components.translation.resolvers.OutputStreamUriParameterResolver;
import com.rackspace.papi.components.translation.resolvers.SourceUriResolverChain;
import com.rackspace.papi.components.translation.xslt.XsltException;
import com.rackspace.papi.components.translation.xslt.XsltParameter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import net.sf.saxon.Controller;
import net.sf.saxon.lib.OutputURIResolver;
import org.apache.xalan.transformer.TrAXFilter;
import org.slf4j.Logger;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

public class XmlFilterChainExecutor {

  private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(XmlFilterChainExecutor.class);
  private static final String REQUEST_ID_PARAM = "requestId";
  private final XmlFilterChain chain;
  private final Properties format = new Properties();
  private final HttpxMarshaller marshaller;

  public XmlFilterChainExecutor(XmlFilterChain chain) {
    this.chain = chain;
    marshaller = new HttpxMarshaller();
    format.put(OutputKeys.METHOD, "xml");
    format.put(OutputKeys.OMIT_XML_DECLARATION, "yes");
    format.put(OutputKeys.ENCODING, "UTF-8");
    format.put(OutputKeys.INDENT, "yes");
  }

  protected SourceUriResolverChain getResolverChain(Transformer transformer) {
    URIResolver resolver = transformer.getURIResolver();
    SourceUriResolverChain resolverChain;
    if (!(resolver instanceof SourceUriResolverChain)) {
      resolverChain = new SourceUriResolverChain(resolver);
      resolverChain.addResolver(new InputStreamUriParameterResolver());
      resolverChain.addResolver(new HttpxUriInputParameterResolver());
      resolverChain.addResolver(new ClassPathUriResolver());
      transformer.setURIResolver(resolverChain);
    } else {
      resolverChain = (SourceUriResolverChain) resolver;
    }

    return resolverChain;

  }

  protected OutputStreamUriParameterResolver getOutputUriResolver(Transformer transformer) {
    if (transformer instanceof Controller) {
      Controller controller = (Controller) transformer;
      OutputURIResolver resolver = controller.getOutputURIResolver();
      if (!(resolver instanceof OutputStreamUriParameterResolver)) {
        resolver = new OutputStreamUriParameterResolver(controller.getOutputURIResolver());
        controller.setOutputURIResolver(resolver);
      }

      return (OutputStreamUriParameterResolver) resolver;
    }

    return null;
  }
  
  private String getRequestId(List<XsltParameter> inputs) {
    for (XsltParameter param: inputs) {
      if (REQUEST_ID_PARAM.equalsIgnoreCase(param.getName()) && param.getValue() != null) {
        return param.getValue().toString();
      }
    }
    
    return UUID.randomUUID().toString();
  }

  protected void setInputParameters(String id, Transformer transformer, List<XsltParameter> inputs) {

    SourceUriResolverChain resolverChain = getResolverChain(transformer);
    com.rackspace.papi.components.translation.resolvers.InputStreamUriParameterResolver resolver = resolverChain.getResolverOfType(InputStreamUriParameterResolver.class);
    resolver.clearStreams();
    
    if (inputs != null && inputs.size() > 0) {
      String uniqueId = getRequestId(inputs);
      HttpxUriInputParameterResolver headersResolver = resolverChain.getResolverOfType(HttpxUriInputParameterResolver.class);
      headersResolver.reset();

      for (XsltParameter input : inputs) {
        if ("*".equals(input.getStyleId()) || id != null && id.equals(input.getStyleId())) {
          String param = null;
          if (input.getValue() instanceof InputStream) {
            param = resolver.addStream((InputStream) input.getValue());
          } else if (input.getValue() instanceof HttpServletRequest) {
            headersResolver.setRequest((HttpServletRequest) input.getValue());
          } else if (input.getValue() instanceof HttpServletResponse) {
            headersResolver.setResponse((HttpServletResponse) input.getValue());
          } else {
            param = input.getValue() != null? input.getValue().toString(): null;
          }

          if (param == null) {
            continue;
          }

          transformer.setParameter(input.getName(), param);
        }
      }
    }

  }

  private void setAlternateOutputs(Transformer transformer, List<XsltParameter<? extends OutputStream>> outputs) {
    OutputStreamUriParameterResolver resolver = getOutputUriResolver(transformer);
    if (resolver != null) {
      resolver.clearStreams();

      if (outputs != null && outputs.size() > 0) {

        String uniqueId = UUID.randomUUID().toString();

        for (XsltParameter<? extends OutputStream> output : outputs) {
          //String paramName = OutputStreamUriParameterResolver.PREFIX + output.getName() + ":" + uniqueId;
          String paramName = resolver.addStream(output.getValue(), output.getName());
          transformer.setParameter("headersOutputUri", paramName);
        }
      }
    }
  }

  public void executeChain(InputStream in, OutputStream output, List<XsltParameter> inputs, List<XsltParameter<? extends OutputStream>> outputs) throws XsltException {
    try {
      for (XmlFilterReference filter : chain.getFilters()) {
        // pass the input stream to all transforms as a param inputstream

        Transformer transformer;
        if (filter.getReader() instanceof net.sf.saxon.Filter) {
          net.sf.saxon.Filter saxonFilter = (net.sf.saxon.Filter) filter.getReader();
          transformer = saxonFilter.getTransformer();
        } else if (filter.getReader() instanceof TrAXFilter) {
          TrAXFilter traxFilter = (TrAXFilter) filter.getReader();
          transformer = traxFilter.getTransformer();
        } else {
          LOG.debug("Unable to set stylesheet parameters.  Unsupported xml filter type used: " + filter.getReader().getClass().getCanonicalName());
          transformer = null;
        }

        if (transformer != null) {
          transformer.clearParameters();
          setInputParameters(filter.getId(), transformer, inputs);
          setAlternateOutputs(transformer, outputs);
        }
      }


      Transformer transformer = chain.getFactory().newTransformer();
      transformer.setOutputProperties(format);
      transformer.transform(getSAXSource(new InputSource(in)), new StreamResult(output));
    } catch (TransformerException ex) {
      throw new XsltException(ex);
    }
  }

  protected SAXSource getSAXSource(InputSource source) {
    if (chain.getFilters().isEmpty()) {
      return new SAXSource(source);
    }

    XMLReader lastFilter = chain.getFilters().get(chain.getFilters().size() - 1).getReader();

    return new SAXSource(lastFilter, source);
  }
}
