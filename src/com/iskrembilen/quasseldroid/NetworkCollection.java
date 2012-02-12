package com.iskrembilen.quasseldroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

public class NetworkCollection extends Observable implements Observer {
	private static final String TAG = NetworkCollection.class.getSimpleName();
	List<Network> networkList = new ArrayList<Network>();
	
	public void addNetwork(Network network) {
		networkList.add(network);
		network.addObserver(this);
		Collections.sort(networkList);
		setChanged();
		notifyObservers();
	}
	
	public Network getNetwork(int location) {
		return networkList.get(location);
	}
	
	public Buffer getBufferById(int bufferId) {
		for (Network network : networkList) {
			if(network.getStatusBuffer().getInfo().id == bufferId)
				return network.getStatusBuffer();
			if(network.getBuffers().hasBuffer(bufferId)) {
				return network.getBuffers().getBuffer(bufferId);
			}
		}
		return null;
	}
	
	public Buffer getPreviousBufferFromId(int bufferId, boolean incStatus) {
		Buffer prev = null;
		boolean found = false;
		for (Network network : networkList) {
			if (network.getStatusBuffer().getInfo().id == bufferId) {
				found = true;
				break;
			}
			
			if (incStatus)
				prev = network.getStatusBuffer();
			
			for (Buffer buf : network.getBuffers().getRawFilteredBufferList()) {
				if (buf.getInfo().id == bufferId) {
					found = true;
					break;
				}
				
				prev = buf;
			}
			if (found)
				break;
		}
		
		if (prev == null) {
			// Didn't find one, so try looking through backwards to find the last one in the list
			// This can probably be done much better, but this will have to do for now
			for (int i=networkList.size()-1; i>=0; i--) {
				List<Buffer> bufList = networkList.get(i).getBuffers().getRawBufferList();
				for (int ii=bufList.size()-1; ii>=0; ii--) {
					prev = bufList.get(ii);
					break;
				}
				prev = networkList.get(i).getStatusBuffer();
				break;
			}
		}
		
		return prev;
	}
	
	public Buffer getNextBufferFromId(int bufferId, boolean incStatus) {
		Buffer first = null;
		boolean foundCurrent = false;
		for (Network network : networkList) {
			if (first == null && incStatus)
				first = network.getStatusBuffer();
			
			if (network.getStatusBuffer().getInfo().id == bufferId) {
				if (incStatus && foundCurrent)
					return network.getStatusBuffer();
				foundCurrent = true;
			}
			
			for (Buffer buf : network.getBuffers().getRawFilteredBufferList()) {
				if (first == null)
					first = buf;
				
				if (foundCurrent)
					return buf;
				
				if (buf.getInfo().id == bufferId)
					foundCurrent = true;
			}
		}
		
		return first;
	}
	
	public Network getNetworkById(int networkId) {
		for(Network network : networkList) {
			if(network.getId() == networkId)
				return network;
		}
		return null;
	}
	
	public void addBuffer(Buffer buffer) {
		int id = buffer.getInfo().networkId;
		for(Network network : networkList) {
			if(network.getId() == id) {
				network.addBuffer(buffer);
				return;
			}
		}
		throw new RuntimeException("Buffer + " + buffer.getInfo().name + " has no valide network id " + id);
	}

	public List<Network> getNetworkList() {
		return networkList;
	}

	public int size() {
		return networkList.size();
	}

	@Override
	public void update(Observable observable, Object data) {
		setChanged();
		notifyObservers();
		
	}
}
