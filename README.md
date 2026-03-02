# DeFi Wallet Minecraft Plugin

A simple PaperMC plugin PoC to add seemless on-chain currency transfer between players.

## Demo
![transfer_demo](https://github.com/user-attachments/assets/8631dd2f-1e7c-4aa4-9aa7-cbcb4d209cb9)

## Abstract
This plugin uses Privy API to create a new Wallet for each new player. For each wallet, a "master key" is added as an additional signer to allow server-side signing without user input.
100 tokens are minted and added to the player wallet for testing purposes.
All operations are ran through Privy API to delegate security and reliability.

## Tech stack
- Privy for simplified and secure user & wallet management
- Web3j API for Ethereum RPC communication
- Custom token contract deployed on any EVM
- SQL Database to store Privy IDs

## Compiling
- Use Gradle to launch the BuildWithCopy job
- The resulting plugin jar will be in the generated "upload" folder

## Plugin Setup
### Web3
- Deploy a token contract on the chain you want (Ethereum Mainnet, Sepolia, Base...) with the Ownable interface and Mintable ability
- Register a new application on [Privy](https://www.privy.io/)
- Create a new authorization key that will be used as a master key for all wallet operations (be sure to save the private key)
- Create a new wallet with the previous key as an additionnal signer
- Transfer the token ownership to this new wallet

### Minecraft
- Start a [PaperMC](https://papermc.io/downloads/paper) server
- Add the compiled jar in the plugins folder
- Restart the server
- Open the plugins/DefiWallet/config.yml file
- Fill the parameters with your own values

## Configuration
<table>
  <tr>
    <th>Parameter</th>
    <th>Description</th>
    <th>Example</th>
  </tr>
  <tr>
    <td>token_chain_id</td>
    <td>The chain on which your token contract has been deployed</td>
    <td>Mainnet: 1, Sepolia: 11155111...</td>
  </tr>
  <tr>
    <td>chain_rpc</td>
    <td>RPC URL for on-chain operations</td>
    <td>https://sepolia.base.org</td>
  </tr>
  <tr>
    <td>token_contract_address</td>
    <td>Your custom token contract address</td>
    <td>0x...</td>
  </tr>
  <tr>
    <td>token_owner_id</td>
    <td>Privy ID of the wallet owning the token (the Privy wallet we created during setup)</td>
    <td></td>
  </tr>
  <tr>
    <td>privy_app_id</td>
    <td>The ID of your Privy app</td>
    <td></td>
  </tr>
  <tr>
    <td>privy_app_secret</td>
    <td>The secret of your Privy app</td>
    <td></td>
  </tr>
  <tr>
    <td>master_key_id</td>
    <td>The ID of the "master" autorization key we created during setup</td>
    <td></td>
  </tr>
  <tr>
    <td>master_key_secret</td>
    <td>The private key of the "master" autorization key we created during setup</td>
    <td>Be sure to remove the "wallet-auth:" prefix</td>
  </tr>
  <tr>
    <td>user_email_domain</td>
    <td>Every player will be associated to a Privy user. Privy requires a login method so we use an impossible email address by default. This could potentially be used to allow your players to access their wallet outside of Minecraft.</td>
    <td>mc_uuid@none.local</td>
  </tr>
</table>
