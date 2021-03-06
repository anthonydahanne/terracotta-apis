/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Entity API.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package org.terracotta.entity;

import com.google.common.util.concurrent.Futures;

import java.util.concurrent.Future;

import org.junit.Assert;
import org.terracotta.exception.EntityException;
import org.terracotta.exception.EntityUserException;


/**
 * Used for basic testing (not part of the in-process testing framework) of an entity, to act like the client end-point.
 */
public class PassthroughEndpoint implements EntityClientEndpoint {
  private final ClientDescriptor clientDescriptor = new FakeClientDescriptor();
  private ActiveServerEntity<?, ?> entity;
  private EndpointDelegate delegate;
  private final ClientCommunicator clientCommunicator = new TestClientCommunicator();
  private boolean isOpen;

  public PassthroughEndpoint() {
    // We start in the open state.
    this.isOpen = true;
  }

  public void attach(ActiveServerEntity<?, ?> entity) {
    this.entity = entity;
    entity.connected(clientDescriptor);
  }

  @Override
  public byte[] getEntityConfiguration() {
    // This is harmless while closed but shouldn't be called so check open.
    checkEndpointOpen();
    return entity.getConfig();
  }

  @Override
  public void setDelegate(EndpointDelegate delegate) {
    // This is harmless while closed but shouldn't be called so check open.
    checkEndpointOpen();
    Assert.assertNull(this.delegate);
    this.delegate = delegate;
  }

  @Override
  public InvocationBuilder beginInvoke() {
    // We can't create new invocations when the endpoint is closed.
    checkEndpointOpen();
    return new InvocationBuilderImpl();
  }

  private class FakeClientDescriptor implements ClientDescriptor {
  }

  private class InvocationBuilderImpl implements InvocationBuilder {
    private byte[] payload = null;

    @Override
    public InvocationBuilder ackSent() {
      // ACKs ignored in this implementation.
      return this;
    }

    @Override
    public InvocationBuilder ackReceived() {
      // ACKs ignored in this implementation.
      return this;
    }

    @Override
    public InvocationBuilder ackCompleted() {
      // ACKs ignored in this implementation.
      return this;
    }

    @Override
    public InvocationBuilder replicate(boolean requiresReplication) {
      // Replication ignored in this implementation.
      return this;
    }

    @Override
    public InvocationBuilder payload(byte[] payload) {
      this.payload = payload;
      return this;
    }

    @Override
    public InvokeFuture<byte[]> invoke() {
      // Note that the passthrough end-point wants to preserve the semantics of a single-threaded server, no matter how
      // complicated the caller is (since multiple threads often are used to simulate multiple clients or multiple threads
      // using one client).
      // We will synchronize on the entity instance so it will only ever see one caller at a time, no matter how many
      // end-points connect to it.
      synchronized (entity) {
        byte[] result = null;
        EntityException error = null;
        try {
          result = sendInvocation(entity, payload);
        } catch (EntityUserException e) {
          error = e;
        }
        return new ImmediateInvokeFuture<byte[]>(result, error);
      }
    }
    
    private <M extends EntityMessage, R extends EntityResponse> byte[] sendInvocation(ActiveServerEntity<M, R> entity, byte[] payload) throws EntityUserException {
      byte[] result = null;
      try {
        MessageCodec<M, R> codec = entity.getMessageCodec();
        M message = codec.deserialize(payload);
        R response = entity.invoke(clientDescriptor, message);
        result = codec.serialize(response);
      } catch (Exception e) {
        throw new EntityUserException(null, null, e);
      }
      return result;
    }
  }

  public ClientCommunicator clientCommunicator() {
    return clientCommunicator;
  }

  private class TestClientCommunicator implements ClientCommunicator {
    @Override
    public void sendNoResponse(ClientDescriptor clientDescriptor, byte[] payload) {
      if (clientDescriptor == PassthroughEndpoint.this.clientDescriptor) {
        if (null != PassthroughEndpoint.this.delegate) {
          PassthroughEndpoint.this.delegate.handleMessage(payload);
        }
      }
    }

    @Override
    public Future<Void> send(ClientDescriptor clientDescriptor, byte[] payload) {
      sendNoResponse(clientDescriptor, payload);
      return Futures.immediateFuture(null);
    }
  }

  @Override
  public void close() {
    // We can't close twice.
    checkEndpointOpen();
    this.isOpen = false;
    // In a real implementation, this is where a call to the PlatformService, to clean up, would be.
  }

  @Override
  public byte[] getExtendedReconnectData() {
    // This should never be called since there is no reconnect.
    Assert.fail("Reconnect not supported");
    return null;
  }

  @Override
  public void didCloseUnexpectedly() {
    Assert.fail("Not expecting this close");
  }

  private void checkEndpointOpen() {
    if (!this.isOpen) {
      throw new IllegalStateException("Endpoint closed");
    }
  }
}
